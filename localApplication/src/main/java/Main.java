import java.io.File;
import java.io.IOException;
import java.util.Date;

public class Main {

    public static void main(String[] args) {
        long timeBefore = new Date().getTime();
        int arg_length = args.length;
        boolean terminate = false;
        int numOfInputFiles;
        int n;
        if(args[arg_length-1].equals("terminate")){
            terminate = true;
            numOfInputFiles = arg_length-3; // -1 for terminate, -1 for output file, -1 for n
            n= Integer.parseInt(args[arg_length-2]);
        }
        else{ // no terminate argument
            numOfInputFiles = arg_length-2; // -1 for output file, -1 for n
            n= Integer.parseInt(args[arg_length-1]);
        }
        File[] input_files = new File[numOfInputFiles];
        File output_file= new File(args[numOfInputFiles]);
        try {
            output_file.createNewFile(); // if file already exists will do nothing
        } catch (IOException e) {
            System.out.println("[ERROR] creating output file");
            throw new RuntimeException(e);
        }
        for(int i=0; i<numOfInputFiles; i++){
            input_files[i] = new File(args[i]);
        }
        App app = new App(input_files, output_file, n, terminate);
        app.start();
        long timeAfter = new Date().getTime();
        System.out.println("total run time in minutes:" + (timeAfter - timeBefore)/(1000*60));
    }


}