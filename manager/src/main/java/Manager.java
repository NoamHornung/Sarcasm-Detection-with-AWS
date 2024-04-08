import static java.lang.Thread.sleep;
import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import software.amazon.awssdk.services.sqs.model.Message;


public class Manager {

    final static AWS aws = AWS.getInstance();

    private final String workerJarName = "Worker-jar-with-dependencies.jar";
    private final String workerJarKey= "workerJarKey";

    public final int MAX_WORKER_INSTANCES = 8;
    public final String DELIMITER= "delimiter#";
    public final String DELIMITER2= "delimiter%"; //to use between the result parts of the review (link, sentiment, entity, sarcasm)

    private ConcurrentHashMap<String, LocalAppInfo> localAppsMap = new ConcurrentHashMap<>();
    private AtomicInteger numOfActiveWorkers = new AtomicInteger(0);
    private AtomicBoolean terminate = new AtomicBoolean(false);
    private boolean isTerminating = false;

    private Object calculatingNumberOfWorkersLock = new Object();

    ExecutorService executorService; // threads pool

    public Manager(){
        System.out.println("[DEBUG] manager created");
    }

    public void start(){
        aws.createQueue(aws.ManagerToWorkersQueueName);
        aws.createQueue(aws.WorkersToManagerQueueName);
        System.out.println("[DEBUG] manager created queues");

        //starts the listeners to the app queue
        executorService = Executors.newCachedThreadPool();
        executorService.execute(new AppsQueueListener(this, aws.AppsToManagerQueueName));
        executorService.execute(new WorkersQueueListener(this, aws.WorkersToManagerQueueName));
        executorService.execute(new HeartbeatMsgSender(this));
        System.out.println("[DEBUG] manager created threads and started listening to queues");
        // try {
        //     executorService.awaitTermination(20, TimeUnit.MINUTES);
        // } catch (InterruptedException e) {
        //     throw new RuntimeException(e);
        // }
    }

    public void handleTaskDistribution(LocalAppInfo localApp){
        // for every app we create a thread that will parser the json file and send the tasks to the workers
        executorService.execute(new TasksDistributer(this, localApp));
    }

    public void handleTaskCompleted(LocalAppInfo localApp){ 
        // create a thread that will combine the results, write them to a file and send it the local app
        // also calls updateTotal_n_reviewsPerWorker, calls updateWorkers and remove the local app from the map
        executorService.execute(new processCompletedTask(this, localApp)); 
    }


    public void deleteMessageFromQueue(String queueName, Message message){
        aws.deleteMessageFromQueue(queueName, message);
    }

    public void handleTermination(){ 
        while(true){
            if(localAppsMap.isEmpty()){
                System.out.println("[DEBUG] manager is terminating");
                isTerminating = true;

                //close all the threads
                executorService.shutdown();

                aws.terminateAllWorkersInstances();
                System.out.println("[DEBUG] manager terminated all workers");

                //deleting queues
                aws.deleteQueue(aws.ManagerToWorkersQueueName);
                aws.deleteQueue(aws.WorkersToManagerQueueName);
                aws.deleteQueue(aws.AppsToManagerQueueName);
                //when every local app finished she delete her unique queue from the manger to local app
                System.out.println("[DEBUG] manager deleted all queues");

                // deleting all the files in the bucket
                aws.deleteAllObjectsFromBucket(aws.bucketName);
                aws.deleteBucket(aws.bucketName);
                System.out.println("[DEBUG] manager deleted bucket");

                //terminate the manager
                aws.terminateInstance(aws.getInstanceId());

                break;
            }
            else{
                try{
                    System.out.println("[DEBUG] manager is waiting for all the local apps to finish");
                    sleep(60000); // wait for the manager to finish all the existing tasks
                }
                catch(InterruptedException e){
                    System.out.println("[ERROR] sleep interrupted");
                }
            }
        }
    }

    
    public void updateWorkers(){
        synchronized(calculatingNumberOfWorkersLock){
            System.out.println("[DEBUG] manager is updating workers");
            int numOfWorkersNeeded = 0;
            for(LocalAppInfo localApp: localAppsMap.values()){
                int numOfWorkersNeededForApp = localApp.getNumOfWorkersNeeded();
                numOfWorkersNeeded += numOfWorkersNeededForApp;
            }
            int currentNumOfActiveWorkers = numOfActiveWorkers.get();
            if(numOfWorkersNeeded > currentNumOfActiveWorkers){
                int numOfNewWorkers = numOfWorkersNeeded - currentNumOfActiveWorkers;
                if(numOfNewWorkers+ currentNumOfActiveWorkers > MAX_WORKER_INSTANCES){
                    numOfNewWorkers = MAX_WORKER_INSTANCES - currentNumOfActiveWorkers;
                }
                String userData= "#!/bin/bash\n" +
                    "echo Worker script is running\n" +
                    // Create directory for the worker files
                    "mkdir worker_files\n" +
                    // Download the jar from S3- aws s3 cp s3://your-bucket/your-app.jar /your-app-directory/your-app.jar
                    "aws s3 cp " + "s3://" + aws.bucketName + "/" + workerJarKey + " ./worker_files/" + workerJarName + "\n" +
                    // run the jar
                    "java -Xmx3750m -Xms3500m -jar /worker_files/" + workerJarName +"\n";
                String ami= "ami-034f8cf25b169bfcc";
                for(int i=0; i<numOfNewWorkers; i++){
                    aws.createInstance(userData, ami, aws.workerTag);
                }
                numOfActiveWorkers.addAndGet(numOfNewWorkers);
                System.out.println("[DEBUG] manager added " + numOfNewWorkers + " workers, current number of active workers: " + numOfActiveWorkers.get());
            }
            else if(numOfWorkersNeeded < currentNumOfActiveWorkers){
                int numOfWorkersToRemove = currentNumOfActiveWorkers - numOfWorkersNeeded;
                for(int i=0; i<numOfWorkersToRemove; i++){
                    terminateWorker();
                }
                numOfActiveWorkers.addAndGet((-1) * numOfWorkersToRemove);
                System.out.println("[DEBUG] manager removed " + numOfWorkersToRemove + " workers, current number of active workers: " + numOfActiveWorkers.get());
            }
        }
    }

    public void terminateWorker(){
        String message = AWS.MessageType.terminate.toString();
        aws.sendMessageToQueue(aws.ManagerToWorkersQueueName, message);
    }

    public String getBucketName(){
        return aws.bucketName;
    }

    public ConcurrentHashMap<String, LocalAppInfo> getLocalAppsMap(){
        return localAppsMap;
    }

    public boolean shouldTerminate(){
        return terminate.get();
    }

    public void addLocalAppToMap(String appName, LocalAppInfo LocalAppInfo){
        synchronized(calculatingNumberOfWorkersLock){
            localAppsMap.putIfAbsent(appName, LocalAppInfo);
        }
    }

    public void removeLocalAppFromMap(String appName){
        synchronized(calculatingNumberOfWorkersLock){
            localAppsMap.remove(appName);
        }
    }

    public void terminate(){
        terminate.set(true);
    }

    public Message getMessageFormQueue(String queueName){
        return aws.getOneMessageFromQueue(queueName);
    }

    public void sendMessageToQueue(String queueName, String message){
        aws.sendMessageToQueue(queueName, message);
    }

    public String downloadFileFromS3(String bucketName, String key){
        return aws.getObjectFromS3(bucketName, key);
    }

    public void uploadFileToS3(String bucketName, String key, File file){
        aws.uploadFileToS3(bucketName, key, file);
    }

    public String getManagerToWorkersQueueName(){
        return aws.ManagerToWorkersQueueName;
    }

    public boolean isTerminating(){
        return isTerminating;
    }

}