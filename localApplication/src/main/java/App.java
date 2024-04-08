import java.util.Date;
import software.amazon.awssdk.services.sqs.model.Message;
import java.io.*;
import static java.lang.Thread.sleep;

public class App {

    private final String managerJarPath = "/home/noam/ass1/manager/target/Manager-jar-with-dependencies.jar"; //TODO change to the current path
    private final String workerJarPath = "/home/noam/ass1/worker/target/Worker-jar-with-dependencies.jar"; //TODO change to the current path
    private final String managerJarName = "Manager-jar-with-dependencies.jar";
    private final String managerJarKey = "managerJarKey";
    private final String workerJarKey= "workerJarKey";

    final static AWS aws = AWS.getInstance();
    private int n_reviewsPerWorker;
    private boolean terminate;
    private File[] input_files;
    private File output_file;
    private String appName;
    private String keyName;
    final String DELIMITER= "delimiter#";
    final String DELIMITER2= "delimiter%";
    private String ManagerToLocalAppQueueName;
    private static final long MAX_ALLOWED_INACTIVITY = 180000; // in milliseconds, 3 minutes
    private String managerInstanceID;

    public App(File[] input_files, File output_file, int n, boolean terminate) {
        this.input_files = input_files;
        this.output_file = output_file;
        this.n_reviewsPerWorker = n;
        this.terminate = terminate;
        this.appName= "app-" +new Date().getTime();
        this.keyName= "key-" +appName;
        this.ManagerToLocalAppQueueName= aws.ManagerToLocalAppQueueName +appName;
    }

    private void uploadJarsToS3(){
        if(!aws.checkIfFileExistsInS3(aws.bucketName, managerJarKey)){
            aws.uploadFileToS3(aws.bucketName, managerJarKey, new File(managerJarPath));
            System.out.println("[DEBUG] uploaded Manager Jar to S3");
        }
        if(!aws.checkIfFileExistsInS3(aws.bucketName, workerJarKey)){
            aws.uploadFileToS3(aws.bucketName, workerJarKey, new File(workerJarPath));
            System.out.println("[DEBUG] uploaded Worker Jar to S3");
        }
    }

    public void start(){
        aws.createBucketIfNotExists(aws.bucketName);
        uploadJarsToS3();
        if(!aws.managerIsActive()){
            System.out.println("[DEBUG] manager is not active");
            String userDataScript="#!/bin/bash\n"+
                "echo Manager script is running\n" +
                // Create directory for the manager files
                "mkdir manager_files\n" +
                // Download the jar from S3- aws s3 cp s3://your-bucket/your-app.jar /your-app-directory/your-app.jar
                "aws s3 cp s3://" + aws.bucketName + "/" + managerJarKey + " ./manager_files/" + managerJarName + "\n" +
                "echo Run manager.jar\n" +
                // run the jar
                "java -jar /manager_files/" + managerJarName + "\n";
            String ami="ami-034f8cf25b169bfcc";
            managerInstanceID= aws.createInstance(userDataScript, ami, aws.managerTag);
            System.out.println("[DEBUG] manager instance created");
        }
        aws.createQueue(ManagerToLocalAppQueueName); // unique queue for each app
        aws.createQueue(aws.AppsToManagerQueueName); // same queue for all apps, created if not exists
        int num_of_files= input_files.length;
        String[] uniqueKeys= new String[num_of_files];
        for(int i=0; i<num_of_files;i++){
            String uniqueKey= keyName + i; // key-<app-name><file-index>
            uniqueKeys[i]= uniqueKey;
            aws.uploadFileToS3(aws.bucketName, uniqueKey, input_files[i]);
            System.out.println("[DEBUG] uploaded file " + input_files[i].getName() + " to S3");
        }
        String message= createNewTaskMessageForManager(n_reviewsPerWorker, num_of_files, uniqueKeys);
        aws.sendMessageToQueue(aws.AppsToManagerQueueName, message);
        System.out.println("[DEBUG] sent new task message to manager");
        waitForResult();
        if(terminate){
            String messageToSend= AWS.MessageType.terminate.toString();
            aws.sendMessageToQueue(aws.AppsToManagerQueueName, messageToSend);
            System.out.println("[DEBUG] sent terminate message to manager");
        }
        aws.deleteQueue(ManagerToLocalAppQueueName);
        System.out.println("[DEBUG] app " + appName + " finished");
    }

    private String createNewTaskMessageForManager(int n_reviewsPerWorker, int num_of_files, String[] uniqueKeys){
        StringBuilder message = new StringBuilder();
        message.append(AWS.MessageType.newTask.toString());
        message.append(DELIMITER);
        message.append(appName);
        message.append(DELIMITER);
        message.append(ManagerToLocalAppQueueName);
        message.append(DELIMITER);
        message.append(n_reviewsPerWorker);
        message.append(DELIMITER);
        message.append(aws.bucketName);
        message.append(DELIMITER);
        message.append(num_of_files);
        message.append(DELIMITER);
        for(String uniqueKey: uniqueKeys){
            message.append(uniqueKey);
            message.append(DELIMITER);
        }
        return message.toString();
        //message format: newTask<delimiter><app-name><delimiter><queue-name><delimiter><n_reviewsPerWorker><delimiter><bucket-name><delimiter><num-of-files><delimiter><key1><delimiter><key2><delimiter>...
    }


    private void waitForResult(){
        System.out.println("[DEBUG] waiting for result");
        long lastHeartbeatTimestamp = System.currentTimeMillis();
        while(true){
            Message message = aws.getOneMessageFromQueue(ManagerToLocalAppQueueName);
            System.out.println("[DEBUG] waiting for message from manager");
            //if the queue is empty, the getOneMessageFromQueue will wait for up to 20 seconds
            //for a message to arrive before returning a null.
            if(message!=null){
                String[] messageParts= message.body().split(DELIMITER);
                if(messageParts[1].equals(appName) && messageParts[0].equals(AWS.MessageType.taskCompleted.toString())){
                    System.out.println("[DEBUG] received taskCompleted message from manager");
                    String bucketName= messageParts[2];
                    String keyName= messageParts[3];
                    // message format: <taskCompleted><app-name><bucket-name><keyName>
                    String resultFile= aws.getObjectFromS3(bucketName, keyName);
                    aws.deleteMessageFromQueue(ManagerToLocalAppQueueName, message);
                    createHTML(resultFile, output_file);
                    return;
                }
                else if(messageParts[0].equals(AWS.MessageType.heartbeat.toString())){
                    System.out.println("[DEBUG] received heartbeat message from manager");
                    lastHeartbeatTimestamp = Long.parseLong(messageParts[1]);
                    aws.deleteMessageFromQueue(ManagerToLocalAppQueueName, message);
                }
                else{
                    System.out.println("[ERROR] received unknown message from manager");
                    aws.deleteMessageFromQueue(ManagerToLocalAppQueueName, message);
                }
            }
            else{
                if(System.currentTimeMillis() - lastHeartbeatTimestamp > MAX_ALLOWED_INACTIVITY){
                    System.out.println("[DEBUG] manager is inactive");
                    resetManager();
                    return;
                }
            }
        }
    }

    private void createHTML(String resultFile, File outputFile){
        System.out.println("[DEBUG] creating HTML file");
        // resultFile structure is: <result per review><DELIMITER><result per review><DELIMITER>...
        // result per review structure is: <link><DELIMITER2><sentiment><DELIMITER2><entity><DELIMITER2><sarcasm>
        String[] results= resultFile.split(DELIMITER);
        StringBuilder htmlContent = new StringBuilder();
        htmlContent.append("<!DOCTYPE html><html><body><table border=\"2\"><tr><th>Link to review</th><th>Entities</th><th>Is sarcasm</th></tr>");
        for(String result: results){
            String[] resultParts= result.split(DELIMITER2);
            String link= resultParts[0];
            int sentiment= Integer.parseInt(resultParts[1]);
            String entity= resultParts[2];
            String sarcasm= resultParts[3];
            String color= getColorForSentiment(sentiment);
            String reviewLine = String.format(
                "<tr><th><a href='%s' style='color: %s; text-decoration: none;'>%s</a></th><th>  %s  </th><th>  %s  </th></tr>",
                    link, color, link, entity, sarcasm);
            htmlContent.append(reviewLine);
        }
        htmlContent.append("</table></body></html>");
        
        // Write HTML content to the output file
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write(htmlContent.toString());
            System.out.println("[DEBUG] HTML file created: " + outputFile.getPath());
            // No need to call writer.flush() explicitly
            // The try-with-resources statement will automatically call writer.close() that also flushes the stream
        } catch (IOException e) {
            System.err.println("[ERROR] Failed to write HTML file: " + e.getMessage());
            e.printStackTrace();
        }

    }

    private String getColorForSentiment(int sentiment){
        switch (sentiment) {
            case 0:
                return "darkred";
            case 1:
                return "red";
            case 2:
                return "black";
            case 3:
                return "lightgreen";
            case 4:
                return "darkgreen";
            default:
                return "blue";
        }
    }

    
    private void resetManager(){
        System.out.println("[DEBUG] resetting manager");
        if(managerInstanceID!=null){
            aws.terminateAllWorkersInstances();
            aws.deleteQueue(ManagerToLocalAppQueueName);
            aws.deleteQueue(aws.AppsToManagerQueueName);
            aws.deleteQueue(aws.ManagerToWorkersQueueName);
            aws.deleteQueue(aws.WorkersToManagerQueueName);
            aws.terminateInstance(managerInstanceID);
        }
        else{
            aws.sendMessageToQueue(aws.AppsToManagerQueueName, AWS.MessageType.terminate.toString());
        }
        try{
            sleep(90*1000); // 90 seconds
        }
        catch(InterruptedException e){
            System.out.println("[ERROR] sleep interrupted");
        }
        start();
    }
}
