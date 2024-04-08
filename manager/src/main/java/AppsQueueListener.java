import java.util.Arrays;
import software.amazon.awssdk.services.sqs.model.Message;

public class AppsQueueListener implements Runnable{

    Manager manager;
    String AppsToManagerQueueName;

    public AppsQueueListener(Manager manager, String AppsToManagerQueueName) {
        this.manager = manager;
        this.AppsToManagerQueueName = AppsToManagerQueueName;
    }

    @Override
    public void run() {
        System.out.println("[DEBUG] AppsQueueListener Thread " + Thread.currentThread().getId() + " is running");
        while(!manager.shouldTerminate()){ // won't accept new tasks if termination message was received
            Message message = manager.getMessageFormQueue(AppsToManagerQueueName);
            if(message != null){
                String[] messageParts = message.body().split(manager.DELIMITER);
                System.out.println("[DEBUG] AppsQueueListener received message from local app: " + Arrays.toString(messageParts)+ "in thread "+ Thread.currentThread().getId());
                if(messageParts[0].equals(AWS.MessageType.newTask.toString())){
                    //message format: <newTask><app-name><queue-name><n_reviewsPerWorker><bucket-name><num-of-files><key1><key2>...
                    String appName = messageParts[1];
                    String ManagerToLocalAppQueueName = messageParts[2];
                    int n_reviewsPerWorker = Integer.parseInt(messageParts[3]);
                    String bucketName = messageParts[4];
                    int num_of_files = Integer.parseInt(messageParts[5]);
                    String[] uniqueKeys = new String[num_of_files];
                    for(int i=0; i<num_of_files; i++){
                        uniqueKeys[i] = messageParts[6+i];
                    }
                    LocalAppInfo localAppInfo = new LocalAppInfo(uniqueKeys, num_of_files, appName, ManagerToLocalAppQueueName, bucketName, n_reviewsPerWorker);
                    manager.addLocalAppToMap(appName, localAppInfo);
                    manager.getLocalAppsMap().get(appName);
                    manager.handleTaskDistribution(localAppInfo);
                    manager.deleteMessageFromQueue(AppsToManagerQueueName, message);
                }
                else if(messageParts[0].equals(AWS.MessageType.terminate.toString())){
                    //message format: <terminate>
                    System.out.println("[DEBUG] AppsQueueListener received terminate message from local app");
                    manager.terminate();
                    manager.deleteMessageFromQueue(AppsToManagerQueueName, message);
                    manager.handleTermination();
                }
                else{
                    System.out.println("[ERROR] received unknown message from local app");
                    manager.deleteMessageFromQueue(AppsToManagerQueueName, message);
                }
            }
        }
        System.out.println("[DEBUG] AppsQueueListener Thread " + Thread.currentThread().getId() + " is finished");
    }
      
}
