import software.amazon.awssdk.services.sqs.model.Message;

public class WorkersQueueListener implements Runnable{

    Manager manager;
    String WorkersToManagerQueueName;

    public WorkersQueueListener(Manager manager, String WorkersToManagerQueueName) {
        this.manager = manager;
        this.WorkersToManagerQueueName = WorkersToManagerQueueName;
    }

    @Override
    public void run() {
        System.out.println("[DEBUG] WorkersQueueListener Thread " + Thread.currentThread().getId() + " is running");
        while(!manager.isTerminating()){
            //System.out.println("[DEBUG] WorkersQueueListener Thread " + Thread.currentThread().getId() +" is listening to queue");
            Message message = manager.getMessageFormQueue(WorkersToManagerQueueName);
            if(message != null){
                String[] messageParts = message.body().split(manager.DELIMITER);
                //System.out.println("[DEBUG] WorkersQueueListener received message from worker: " + Arrays.toString(messageParts));
                if(messageParts[0].equals(AWS.MessageType.taskCompleted.toString())){
                    //message format: <taskCompleted><app-name><key><result>
                    String appName = messageParts[1];
                    String key = messageParts[2];
                    String result = messageParts[3]; // <link><DELIMITER2><sentiment><DELIMITER2><entity><DELIMITER2><sarcasm>
                    //System.out.println("[DEBUG] received taskCompleted for " + appName + " with key " + key);
                    LocalAppInfo localApp = manager.getLocalAppsMap().get(appName);
                    localApp.addResult(key, result); // key is unique- the index of the review
                    localApp.decrementReviewsLeft();
                    if(localApp.getReviewsLeft()==0){
                        manager.handleTaskCompleted(localApp);
                    }
                }
                else{
                    System.out.println("[ERROR] received unknown message from worker");
                }
                manager.deleteMessageFromQueue(WorkersToManagerQueueName, message);
            }
        }
        System.out.println("[DEBUG] WorkersQueueListener Thread " + Thread.currentThread().getId() + " is finished");
    }
    
}
