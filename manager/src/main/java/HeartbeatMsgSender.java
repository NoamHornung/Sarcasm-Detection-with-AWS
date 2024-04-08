import java.util.Collection;

public class HeartbeatMsgSender implements Runnable{

    private Manager manager;
    int timeInterval = 30000; // 30 seconds

    public HeartbeatMsgSender(Manager manager) {
        this.manager = manager;
    }

    @Override
    public void run() {
        System.out.println("[DEBUG] HeartbeatMsgSender Thread " + Thread.currentThread().getId() + " is running");
        // send heartbeat message to all local apps every timeInterval
        while(!manager.isTerminating()){
            Collection<LocalAppInfo> localApps  = manager.getLocalAppsMap().values();
            String message = createHeartbeatMsg();
            for(LocalAppInfo localApp : localApps){
                String ManagerToLocalAppQueueName = localApp.getManagerToLocalAppQueueName();
                manager.sendMessageToQueue(ManagerToLocalAppQueueName, message);
            }
            //System.out.println("[DEBUG] HeartbeatMsgSender sent heartbeat message to all local apps");
            try {
                Thread.sleep(timeInterval); 
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        System.out.println("[DEBUG] HeartbeatMsgSender Thread " + Thread.currentThread().getId() + " is finished");
    }


    private String createHeartbeatMsg(){
        //message format: <heartbeat><DELIMITER><current time>
        StringBuilder message = new StringBuilder();
        message.append(AWS.MessageType.heartbeat.toString());
        message.append(manager.DELIMITER);
        message.append(System.currentTimeMillis());
        return message.toString();
    }

    
}