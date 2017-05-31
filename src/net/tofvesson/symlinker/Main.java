package net.tofvesson.symlinker;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.prefs.Preferences;

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception{
        Parent root = FXMLLoader.load(getClass().getResource("sample.fxml"));
        primaryStage.setTitle("Hello World");
        primaryStage.setScene(new Scene(root, 300, 275));
        primaryStage.show();
    }


    public static void main(String[] args) throws Throwable {

        if(!isAdmin()){
            System.out.println("Not admin");
            System.exit(-1);
        }

        File source = new File(System.getProperty("user.dir")+File.separatorChar+"source"+File.separatorChar),
                destination = new File(System.getProperty("user.dir")+File.separatorChar+"destination"+File.separatorChar);

        moveAndLink(source, destination);

        launch(args);
    }

    protected static void moveAndLink(File source, File destination) throws IOException, InterruptedException {
        boolean dir = source.isDirectory();

        FileMover mover = new FileMover(source, destination, FileMover.DEFAULT_BUFFER_SIZE, null);

        while(mover.isAlive()){
            System.out.println("Move progress: "+(mover.getCurrentTransferSize().doubleValue()/mover.getTotalSize().doubleValue())*100+"%");
            try { Thread.sleep(1); }catch(InterruptedException ignored){}
        }
        System.out.println("Move progress: 100%");
        //try { Thread.sleep(25); }catch(InterruptedException ignored){} // Shouldn't be necessary

        Runtime.getRuntime().exec("cmd /c \"mklink "+(dir ? "/J " : "")+"\""+source.getAbsolutePath()+"\" \""+destination.getAbsolutePath()+"\"\"");
    }

    public static boolean isAdmin(){
        PrintStream o = System.err;
        // Silent error stream because not explicitly printing errors isn't enough to stop errors from being printed
        System.setErr(new PrintStream(new OutputStream() { @Override public void write(int i) throws IOException { } }));
        Preferences prefs = Preferences.systemRoot();
        try{
            prefs.put("foo", "bar"); // SecurityException on Windows
            prefs.remove("foo");
            prefs.flush(); // BackingStoreException on Linux
            return true;
        }catch(Exception e){
            return false;
        }finally{ System.setErr(o); }
    }
}
