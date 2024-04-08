import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public class TasksDistributer implements Runnable{

    Manager manager;
    LocalAppInfo LocalAppInfo;
    String ManagerToWorkersQueueName;

    public TasksDistributer(Manager manager, LocalAppInfo LocalAppInfo) {
        this.manager = manager;
        this.LocalAppInfo = LocalAppInfo;
        this.ManagerToWorkersQueueName = manager.getManagerToWorkersQueueName();
    }

    @Override
    public void run() {
        String appName = LocalAppInfo.getAppName();
        System.out.println("[DEBUG] TasksDistributer Thread " + Thread.currentThread().getId() + " is running for app " + appName);
        Long startTime = System.currentTimeMillis();
        String bucketName = LocalAppInfo.getBucketName();
        String[] uniqueKeys = LocalAppInfo.getUniqueKeys();
        int num_of_files = LocalAppInfo.getNum_of_files();
        int reviewIndex = 1;
        List<String> messagesForWorkers = new ArrayList<>();
        for(int i=0; i< num_of_files; i++){ // for each file the local app uploaded
            String key = uniqueKeys[i];
            String inputJsonFile = manager.downloadFileFromS3(bucketName, key);
            List<String> extractedReviewsData = jsonReviewsExtractor(inputJsonFile);
            for(String review: extractedReviewsData){ // for each review in the file
                String message = AWS.MessageType.newTask.toString() + manager.DELIMITER + appName + manager.DELIMITER + review + manager.DELIMITER + reviewIndex;
                // message format: <newTask><DELIMITER><app-name><DELIMITER><link><DELIMITER><text><DELIMITER><rating><DELIMITER><reviewIndex>
                messagesForWorkers.add(message);
                reviewIndex++;
            }
            //System.out.println("[DEBUG] TasksDistributer finished processing file " + key);
        }
        int numOfMessages = messagesForWorkers.size();
        LocalAppInfo.setReviewsLeft(numOfMessages);
        int n_reviewsPerWorker= LocalAppInfo.getReviewsPerWorker();
        int numOfWorkersNeeded = (int) Math.ceil((double)numOfMessages / n_reviewsPerWorker);
        LocalAppInfo.setNumOfWorkersNeeded(numOfWorkersNeeded);
        manager.updateWorkers();
        for(String message: messagesForWorkers){
            manager.sendMessageToQueue(ManagerToWorkersQueueName, message);
        }
        Long endTime = System.currentTimeMillis();
        System.out.println("[DEBUG] TasksDistributer sent " + numOfMessages + " messages(reviews) to workers for app " + appName);
        System.out.println("[DEBUG] TasksDistributer Thread " + Thread.currentThread().getId() + " is finished for app " + appName + " at total of " + (endTime-startTime)/1000 + " seconds");
    }

    public List<String> jsonReviewsExtractor(String jsonFile){
        List<String> extractedReviewsData = new ArrayList<>();
        
        JsonFactory factory = new JsonFactory();
        ObjectMapper mapper = new ObjectMapper();

        try (JsonParser parser = factory.createParser(jsonFile)) {
            while (parser.nextToken() != null) {
                JsonNode jsonNode = mapper.readTree(parser);
                if (jsonNode != null && jsonNode.has("reviews")) {
                    JsonNode reviewsArray = jsonNode.get("reviews");

                    Iterator<JsonNode> reviewsIterator = reviewsArray.elements();
                    while (reviewsIterator.hasNext()) {
                        JsonNode review = reviewsIterator.next();

                        String link = review.get("link").asText();
                        String text = review.get("text").asText();
                        int rating = review.get("rating").asInt();

                        String reviewData = link + manager.DELIMITER + text + manager.DELIMITER + rating;
                        extractedReviewsData.add(reviewData);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return extractedReviewsData;
    }
    
}
