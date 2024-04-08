# Sarcasm-Detection-with-AWS
This project involves developing a real-world application to process Amazon reviews distributively, perform sentiment analysis, and detect sarcasm. The application consists of both local and non-local instances running on Amazon Web Services (AWS).

Key Components:
- Local Application:
Accepts input text files containing Amazon reviews in JSON format.
Takes an integer input 'n', specifying the number of reviews/messages per worker.
Optionally, accepts a 'terminate' argument indicating that the local application will send a terminate message to the Manager at the end of its task.

AWS Instances:
- Workers: Instances responsible for performing sentiment analysis on the reviews.
- Manager: Coordinates the workers and manages the overall processing task.

Main Use-case:
User provides input files containing reviews, specifies the number of reviews per worker, and optionally requests termination.
Results are displayed on a webpage.

Input Files: Input files provided in JSON format containing lists of reviews.

Output: An HTML file containing:
Links to the original reviews, colored based on sentiment.
Named entities found in the reviews (comma-separated).
Sarcasm detection based on the number of stars given by the user for sentiment analysis.

Sarcasm Detection: A simple algorithm is applied based on the number of stars given by the user for sentiment analysis. If the stars align with the sentiment analysis, no sarcasm is detected; otherwise, it appears to be sarcasm.


# Instructions to run the project:

1. Set your AWS credentials in ~user/.aws/credentials files.
2. Build the JAR files for the manager and the worker by navigating to the root directory of each and executing the 'mvn clean package' command.
3. After building, find the Worker-jar-with-dependencies.jar in the "worker/target" directory and the Manager-jar-with-dependencies.jar in the "manager/target" directory.
4. Relocate these JAR files to your preferred directory.
5. In the "localApplication/src/main/java/App.java" file, set the variables "managerJarPath" and "workerJarPath" to the current paths of the JARs.
6. Build the JAR file for the localApplication by executing the 'mvn clean package' command in the root directory of the localApplication.
7. After building, find the local-application-jar-with-dependencies.jar in the "localApplication/target" directory.
8. Move the "local-application-jar-with-dependencies.jar" file to a directory of your choice, and ensure that your input files are placed in the same directory as well.
9. Run the localApplication with the required arguments using the following command:
   java -jar local-application-jar-with-dependencies.jar inputFileName1... inputFileNameN outputFileName.html n [terminate]
   where:
   -"inputFileNameI" is the name of the input file I.
   -"outputFileName" is the name of the output file.
   -"n" is the workers’ files ratio (reviews per worker).
   -"terminate" indicates that the application should terminate the manager at the end (not mandatory).
   Note: We assume that all given arguments are valid.
10. Wait till the output file created, and see the results.

# How our program works:
- localApplication:
    - If the manager and worker JARs haven't been uploaded to Amazon's S3 storage service previously by another localApplication, they are uploaded. Please note that the worker JAR is a heavy file, so it may take a few minutes to be uploaded to S3.
    - It verifies if a manager instance is running; if not, it creates one.
    - It establishes a SQS queue, named "AppsToManagerQueue," for all applications to communicate with the manager.
    - Additionally, it creates a unique SQS queue for the manager to send messages to this specific application, named "ManagerToLocalAppQueue"+appName.
    - The input files are uploaded to S3.
    - Next, it sends a new task message to the "AppsToManagerQueue" with the input file keys in S3 and the specified reviews per worker (n).
    - The application then awaits a result message from the manager. In the meantime, it receives heartbeat messages from the manager, containing the timestamp of when the message was sent. If a specified time threshold (MAX_ALLOWED_INACTIVITY milliseconds) is exceeded without receiving a heartbeat message, the manager is restarted.
    - Upon receiving the result message from the manager, the application retrieves the result file from S3 and generates an HTML file representing the results, which is saved as the outputFileName.
    - If a terminate argument was provided, the application sends a termination message to the Manager.

- Manager:
    - The manager initializes SQS queues named ManagerToWorkersQueue and WorkersToManagerQueue.
    - Subsequently, the manager initiates several threads:
        - AppsQueueListener: This thread continuously checks for messages in the AppsToManagerQueue.
        - WorkersQueueListener: This thread continuously checks for messages in the WorkersToManagerQueue.
        - HeartbeatMsgSender: This thread is responsible for sending heartbeat messages to all the localApplications at regular intervals defined by the 'timeInterval'.
    - Upon receiving a new task from a localApplication, the manager creates a TasksDistributor thread:
        - This thread downloads the input files sent to S3 and split them into separate reviews.
        - It calculates the required number of workers to run based on the application's reviews per worker (n) and the total number of reviews.
        - If necessary, it creates additional worker instances. Please note that there exists a constant, MAX_WORKERS_INSTANCES, which imposes a limitation on the maximum number of worker instances that can be created. This limitation is in place due to restrictions imposed by AWS on student accounts. As a result, we cannot exceed this specified number when creating worker instances.
        - Finally, it sends the review task messages to the ManagerToWorkersQueue for distribution to the workers.
    - Upon completing all tasks for a localApplication, the manager initiates a ProcessCompletedTask thread:
        - It compiles all task results into an output file and uploads it to S3.
        - Subsequently, it sends a message to the localApplication containing the S3 file key.
        - It calculates the number of workers required for other active localApplications. If the current number of workers exceeds this calculated requirement, it terminates the excess workers to optimize resource usage.
    - If a terminate message is received from a localApplication, the manager ceases handling new messages from new localApplications. However, it completes its remaining tasks before it terminates all the workers, deletes all the queues and buckets, and terminate itself.

- Worker:
    - Each worker fetches a message from the ManagerToWorkersQueue, processes it, sends the output to the WorkersToManagerQueue, and then waits for the next task message.
    - This process continues until the worker receives a termination message from the manager, at which point it stops processing tasks and terminates.

Our program's instances types:
    - ami-034f8cf25b169bfcc, linux with java 11 installed.
    - type T2_LARGE

Running time:
During our program execution with the parameter n= 100 using the provided example input files and starting with no running instance of the Manager, the program completed its execution in average of 25 minutes.

# Persistence

To ensure smooth operation despite instances' occasional unreliability, we've implemented several strategies:

- Heartbeat Message Protocol (as described before): Our protocol guarantees that if an issue arises, such as a manager instance failure, we restart the instance and resend the message for processing. This ensures continuous operation and prevents message loss.
- Worker Message Handling: When a worker claims a message for processing, we take precautions to prevent message loss and ensure that multiple instances of the same message are not handled simultaneously. Upon receipt of a message, it remains in the queue. To prevent other consumers from processing the message again, we set in SQS a visibility timeout, during which all consumers are prevented from receiving and processing the message. Moreover, if a worker unexpectedly shuts down after claiming a message but fails to delete it (deletion only occurs after sending the output to the manager), the message is automatically returned to the queue. This mechanism ensures that all task messages are ultimately handled by a worker. Additionally, we dynamically extend the visibility timeout of a message as needed to accommodate processing delays and potential worker failures. These measures ensure robust message handling and minimize the risk of message loss.

# Scalability

- Thread Per Client Pattern: The manager operates using a thread per client pattern and efficiently manages them with an Executor Service. This design enables the program to run effectively even with multiple applications, allowing for efficient utilization of resources and improved performance.
- Handling Tasks from Multiple Applications: The manager achieves the capability to handle tasks from multiple applications concurrently by assigning a unique name to each localApplication and utilizing a data structure to store the necessary data for each localApplication. This approach ensures that tasks from different applications are segregated and processed independently. By providing multi-application support, the system enhances scalability, enabling it to efficiently accommodate a larger workload and serve multiple clients simultaneously. This architecture allows for effective management of diverse tasks and resources, contributing to the overall scalability and robustness of the system.
- Shared Queue for Workers: All workers receive messages from the same queue. This approach enhances concurrency and enables workers to process tasks more efficiently. By utilizing a shared queue, the system can distribute tasks effectively among available workers, optimizing resource utilization.
- Dynamic Worker Instance Management: The manager dynamically creates worker instances as needed and terminates them when they are no longer required. This adaptive approach ensures that resources are allocated efficiently based on current demand, thereby optimizing scalability and minimizing resource wastage. Additionally, this dynamic management strategy allows the system to scale up or down seamlessly in response to fluctuating workloads, enhancing overall system flexibility and scalability.

# Security

There are a lot of people who would love to have an access to our AWS account and use the computing resources he needs.
By keeping our credentials separate and inaccessible within the project files, we safeguard against potential misuse of our computing resources by unauthorized individuals.
