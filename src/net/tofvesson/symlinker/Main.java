package net.tofvesson.symlinker;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.prefs.Preferences;

public class Main extends Application {

    protected FileMover currentOperation;
    protected File fSource, fDestination;

    @Override
    public void start(Stage primaryStage) throws Exception{
        // Load layout file
        Parent root = FXMLLoader.load(getClass().getResource("symmie.fxml"));

        TextField source = (TextField) root.lookup("#source"), destination = (TextField) root.lookup("#destination");
        Button b = (Button) root.lookup("#startstop");
        ProgressBar p = (ProgressBar) root.lookup("#progress");
        source.setOnMouseClicked(mouseEvent -> {
            DirectoryChooser chooser = new DirectoryChooser();
            fSource = chooser.showDialog(null);
            source.setText(fSource==null?"":fSource.getAbsolutePath());
        });
        destination.setOnMouseClicked(mouseEvent -> {
            DirectoryChooser chooser = new DirectoryChooser();
            fDestination = chooser.showDialog(null);
            destination.setText(fDestination==null?"":fDestination.getAbsolutePath());
        });
        b.setOnMouseClicked(mouseEvent -> {
            if(currentOperation!=null && currentOperation.isAlive()){
                currentOperation.cancel();
                b.setText("Start");
            }
            else{
                b.setText("Cancel");
                p.setVisible(true);
                currentOperation = new FileMover(
                        fSource = new File(source.getText()),
                        fDestination = new File(destination.getText()),
                        FileMover.DEFAULT_BUFFER_SIZE,
                        true,
                        (s, d) -> System.out.println("Couldn't move \""+s.getAbsolutePath()+"\" to \""+d.getAbsolutePath()+"\"!"),
                        (s, d, success, m) -> Platform.runLater(() -> {
                            if(success== FileMover.MoveState.COMPLETED)
                                try {
                                    Runtime.getRuntime().exec("cmd /c \"mklink /J \""+fSource.getAbsolutePath()+"\" \""+fDestination.getAbsolutePath()+"\"\"");
                                } catch (IOException e) {
                                    e.printStackTrace();
                                }
                            else if(success== FileMover.MoveState.PARTIAL){
                                Alert alert = new Alert(Alert.AlertType.WARNING);
                                alert.setTitle("Move problems");
                                alert.setHeaderText("Some files couldn't be moved.");
                                alert.setContentText("Some or all files/folders could not be moved from the original location. " +
                                        "This is usually due to a lack of permissions or data corruption to the partition! " +
                                        "All files that could be moved were moved. Symbolic link was not created.");
                                alert.showAndWait();
                            }else{
                                Alert alert = new Alert(Alert.AlertType.ERROR);
                                alert.setTitle("Move error");
                                alert.setHeaderText("No files were moved");
                                alert.setContentText("After assessing the state of the source partition and destination partition, " +
                                        "it was determined there might not be enough space to accommodate the new files, " +
                                        "therefore no files were moved. " +
                                        "Please ensure you have enough disk space.");

                                alert.showAndWait();
                            }
                            p.setVisible(false);
                            b.setText("Start");
                        }),
                        (s, d, success, m) -> {
                            System.out.println(wordify(success.name()) + " " + (success == FileMover.MoveState.FAILED ? "to move" : "move from")
                                    + " \"" + s.getAbsolutePath() + "\" to \"" + d.getAbsolutePath() + "\"");
                            Platform.runLater(() -> p.setProgress(m.getTotalSize().doubleValue()==0?1:m.getCurrentTransferSize().divide(m.getTotalSize()).doubleValue()));
                        }
                );
            }
        });

        // Set up display stuff and show
        primaryStage.setTitle("Symmie: Symlinker tool");
        primaryStage.setScene(new Scene(root, root.prefWidth(-1.0D), root.prefHeight(-1.0D)));
        primaryStage.show();

        // TODO: Add error message if not run as admin
    }


    public static void main(String[] args) throws Throwable {
        if(!isAdmin()){ // TODO: Move
            System.err.println("Not admin");
            System.exit(-1);
        }

        // Start JavaFX application
        launch(args);
    }

    // Just for console output. Technically obsolete at this point
    static String wordify(String s){ return s.length()==0?s:s.length()==1?s.toUpperCase():s.toUpperCase().substring(0, 1)+s.toLowerCase().substring(1, s.length()); }

    // Important
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
