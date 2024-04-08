import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;

public class processCompletedTask implements Runnable{

    Manager manager;
    LocalAppInfo localApp;

    public processCompletedTask(Manager manager, LocalAppInfo localApp) {
        this.manager = manager;
        this.localApp = localApp;
    }

    @Override
    public void run() {
        String appName = localApp.getAppName();
        Long startTime = System.currentTimeMillis();
        System.out.println("[DEBUG] processCompletedTask Thread " + Thread.currentThread().getId() + " is running for app " + appName);
        String ManagerToLocalAppQueueName = localApp.getManagerToLocalAppQueueName();
        Map<String, String> resultsMap = localApp.getResultsMap();

        //combine all the results to one string with delimiter between each result
        //resultContent format: <result1><DELIMITER><result2><DELIMITER><result3><DELIMITER>...
        Collection<String> results= resultsMap.values();
        String resultContentString = String.join(manager.DELIMITER, results);
        
        // write the resultsContent to a file
        Path filePath = Path.of("output"+appName+".txt"); //java 11 method else use Paths.get
        try {
            BufferedWriter writer = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            writer.write(resultContentString);
            writer.close();
            System.out.println("[DEBUG] processCompletedTask Thread wrote the results of app " + appName + " to file "+ filePath.toString());
        }
        catch(IOException e){
            System.out.println("[ERROR] processCompletedTask Thread failed to write the results of app " + appName + " to file "+ filePath.toString());
            e.printStackTrace();
        }
        File file = filePath.toFile();
        String uniqueKey= "output"+appName;
        String bucketName= manager.getBucketName();
        manager.uploadFileToS3(bucketName, uniqueKey, file);
        System.out.println("[DEBUG] processCompletedTask Thread uploaded file " + file.getName() + " to S3");
        // message format: <taskCompleted><app-name><bucket-name><keyName>
        String message= AWS.MessageType.taskCompleted.toString() + manager.DELIMITER + appName + manager.DELIMITER + bucketName + manager.DELIMITER + uniqueKey;
        manager.sendMessageToQueue(ManagerToLocalAppQueueName, message);
        System.out.println("[DEBUG] processCompletedTask Thread sent message to local app " + appName + " with the key to the results file in s3");
        file.delete();

        manager.removeLocalAppFromMap(appName);
        manager.updateWorkers();
        Long endTime = System.currentTimeMillis();
        System.out.println("[DEBUG] processCompletedTask Thread " + Thread.currentThread().getId() + " is finished for app " + appName+ " at total of " + (endTime-startTime)/1000 + " seconds");
    }
    
}
