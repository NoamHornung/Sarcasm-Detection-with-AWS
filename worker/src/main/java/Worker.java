import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import software.amazon.awssdk.services.sqs.model.Message;

public class Worker {

    static final AWS aws= AWS.getInstance();

    final String DELIMITER= "delimiter#";
    final String DELIMITER2= "delimiter%";

    static sentimentAnalysisHandler sentimentAnalysisHandler = new sentimentAnalysisHandler();
    static namedEntityRecognitionHandler namedEntityRecognitionHandler = new namedEntityRecognitionHandler();

    String ManagerToWorkersQueueName = aws.ManagerToWorkersQueueName;
    String WorkersToManagerQueueName = aws.WorkersToManagerQueueName;

    public Worker(){
        System.out.println("[DEBUG] worker created");
    }

    public void handleMessages(){
        while (true) {
            Message message = aws.getOneMessageFromQueue(ManagerToWorkersQueueName);
            //String instanceId= aws.getInstanceId();
            if(message!=null){
                AtomicBoolean finishedProcessingMessage= new AtomicBoolean(false);
                //boolean finishedProcessingMessage= false;
                extendMessageVisibility(message, ManagerToWorkersQueueName, finishedProcessingMessage);
                String[] messageParts= message.body().split(DELIMITER);
                if(messageParts[0].equals(AWS.MessageType.newTask.toString())){
                    // message format: <newTask><app-name>><link><text><rating><reviewIndex>
                    String appName= messageParts[1];
                    String link= messageParts[2];
                    String text= messageParts[3];
                    String rating= messageParts[4];
                    String reviewIndex= messageParts[5];
                    long startTime= System.currentTimeMillis();
                    System.out.println("[DEBUG] worker started processing message "+ reviewIndex+ " from "+ appName);
                    int sentiment= sentimentAnalysisHandler.findSentiment(text);
                    List<String> entities= namedEntityRecognitionHandler.extractEntities(text);
                    boolean sarcasm= sarcasmDetection(sentiment, Integer.parseInt(rating));
                    String result= buildResult(link, sentiment, entities, sarcasm);
                    String messageToSend= buildMessageToSend(appName, reviewIndex, result);
                    aws.sendMessageToQueue(WorkersToManagerQueueName, messageToSend);
                    finishedProcessingMessage.set(true);
                    aws.deleteMessageFromQueue(ManagerToWorkersQueueName, message);
                    long endTime= System.currentTimeMillis();
                    System.out.println("[DEBUG] worker finished processing message "+ reviewIndex+ " from "+ appName+ " in "+ (endTime-startTime)/1000+ " seconds");
                }
                if(messageParts[0].equals(AWS.MessageType.terminate.toString())){
                    String instanceId= aws.getInstanceId();
                    finishedProcessingMessage.set(true);
                    aws.deleteMessageFromQueue(ManagerToWorkersQueueName, message);
                    System.out.println("[DEBUG] worker got a terminate message and will terminate itself");
                    aws.terminateInstance(instanceId);
                    return;
                }
            }
        }
    }


    private boolean sarcasmDetection(int sentiment, int rating){
        // sentiment:  0 \= very negative up to 4 \= very positive
        // rating: 1 \= very negative up to 5 \= very positive
        if(rating-sentiment>=3 || rating-sentiment<=-3){
            return true;
        }
        return false;
    }

    private String buildResult(String link, int sentiment, List<String> entities, boolean sarcasm){
        StringBuilder result= new StringBuilder();
        //result string format <link><DELIMITER2><sentiment><DELIMITER2><entity><DELIMITER2><sarcasm>
        result.append(link);
        result.append(DELIMITER2);
        result.append(sentiment);
        result.append(DELIMITER2);
        result.append(entities);
        result.append(DELIMITER2);
        result.append(sarcasm);
        return result.toString();
    }

    private String buildMessageToSend(String appName, String reviewIndex, String result){
        StringBuilder messageToSend= new StringBuilder();
        // messageToSend format: <taskCompleted><DELIMITER><app-name><DELIMITER><key=reviewIndex><DELIMITER><result>
        messageToSend.append(AWS.MessageType.taskCompleted.toString());
        messageToSend.append(DELIMITER);
        messageToSend.append(appName);
        messageToSend.append(DELIMITER);
        messageToSend.append(reviewIndex);
        messageToSend.append(DELIMITER);
        messageToSend.append(result);
        return messageToSend.toString();
    }


    private static void extendMessageVisibility(Message message, String ManagerToWorkersQueueName, AtomicBoolean finishedProcessingMessage) {
        String receipt = message.receiptHandle();
        Thread timerThread = new Thread(() -> {
            try {
                Thread.sleep(30 * 1000); // the queue message visibility timeout is 60 seconds
                while(!finishedProcessingMessage.get()){
                    // Extend the visibility timeout if the work is not finished
                    System.out.println("[DEBUG] worker is extending the visibility timeout of the message");
                    aws.changeMessageVisibility(ManagerToWorkersQueueName, receipt, 120); // Extend by 120 seconds
                    Thread.sleep(110 * 1000); // Wait for the next interval
                }
            } 
            catch(InterruptedException e){
                Thread.currentThread().interrupt();
            }
        });
        timerThread.start();
    }

}
