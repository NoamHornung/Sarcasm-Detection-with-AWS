import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class LocalAppInfo {

    private String[] uniqueKeys;
    private int num_of_files;
    private String appName;
    private String ManagerToLocalAppQueueName;
    private String bucketName;
    private int n_reviewsPerWorker;
    private Map<String, String> resultsMap = new ConcurrentHashMap<>();
    private AtomicInteger reviewsLeft;
    private int numOfWorkersNeeded;


    public LocalAppInfo(String[] uniqueKeys, int num_of_files, String appName, String ManagerToLocalAppQueueName, String bucketName, int n_reviewsPerWorker) {
        this.uniqueKeys = uniqueKeys;
        this.num_of_files = num_of_files;
        this.appName = appName;
        this.ManagerToLocalAppQueueName = ManagerToLocalAppQueueName;
        this.bucketName = bucketName;
        this.n_reviewsPerWorker = n_reviewsPerWorker;
    }

    public String[] getUniqueKeys() {
        return uniqueKeys;
    }

    public int getNum_of_files() {
        return num_of_files;
    }

    public String getAppName() {
        return appName;
    }

    public String getManagerToLocalAppQueueName() {
        return ManagerToLocalAppQueueName;
    }

    public String getBucketName() {
        return bucketName;
    }

    public int getReviewsPerWorker() {
        return n_reviewsPerWorker;
    }

    public Map<String, String> getResultsMap() {
        return resultsMap;
    }

    public int getReviewsLeft() {
        return reviewsLeft.get();
    }

    public void addResult(String key, String reviewResult){
        resultsMap.putIfAbsent(key, reviewResult);
    }

    public int decrementReviewsLeft(){
       return reviewsLeft.decrementAndGet();
    }

    public void setReviewsLeft(int num){
        reviewsLeft = new AtomicInteger(num);
    }

    public int getNumOfWorkersNeeded() {
        return numOfWorkersNeeded;
    }

    public void setNumOfWorkersNeeded(int numOfWorkersNeeded) {
        this.numOfWorkersNeeded = numOfWorkersNeeded;
    }
    
}
