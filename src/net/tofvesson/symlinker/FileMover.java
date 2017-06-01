package net.tofvesson.symlinker;

import java.io.*;
import java.math.BigInteger;

/** @noinspection WeakerAccess, ConstantConditions, unused */
public class FileMover {

    public static final int DEFAULT_BUFFER_SIZE = 8192;
    public static final int MINIMUM_BUFFER_SIZE = 256;

    protected volatile boolean cancel = false;
    protected volatile BigInteger currentTransferSize = BigInteger.ZERO;
    protected final Thread moveOp;
    protected final FileMoveErrorHandler handler;
    protected final OnMoveCompletedListener perFile;
    protected final BigInteger totalSize;
    protected final int bufferSize;
    protected final boolean verbose;

    /**
     * Initiates a file transfer operation from the specified source (be it a single file or an entire folder) to the given destination.
     * @param source Source location to move file(s)/folder(s) from.
     * @param destination Location to move file(s)/folder(s) to.
     * @param bufferSize File transfer data buffer (in bytes).
     * @param verbose Whether or not to print errors in standard error stream.
     * @param errorHandler A callback interface whenever an error with moving is encountered.
     * @param listener A callback for when the move operation is completed.
     * @param perFile A callback called whenever a file transfer is completed.
     */
    public FileMover(File source, File destination, int bufferSize, boolean verbose, FileMoveErrorHandler errorHandler, OnMoveCompletedListener listener, OnMoveCompletedListener perFile){
        handler = errorHandler;
        this.verbose = verbose;
        this.perFile = perFile;
        this.bufferSize = bufferSize < MINIMUM_BUFFER_SIZE ? bufferSize : DEFAULT_BUFFER_SIZE;
        totalSize = enumerate(source, BigInteger.ZERO);
        moveOp = new Thread(()->{
            boolean error;
            // Proactive error handling
            if((error=!destination.isDirectory() && !destination.mkdirs()) ||
                    totalSize.subtract(BigInteger.valueOf(destination.getUsableSpace())).toString().charAt(0)!='-') // Reasons for error
            {
                if(handler!=null) handler.onMoveError(source, destination); // Notify error
                if(verbose) System.err.println(error?"Couldn't create necessary directories!":"Not enough space in destination to accommodate move operation!"); // Print reason for error
                if(listener!=null) listener.onMoveComplete(source, destination, MoveState.FAILED, this);
                return;
            }

            // Move files
            moveAll(source, destination, true);

            // Clean up residual folders :)
            boolean b = !cancel && destroyDirs(source); // Will short-circuit to false if cancel was requested (won't destroy dirs)
            if(b) currentTransferSize = new BigInteger(totalSize.toString()); // If success, just make sure the numbers match :)
            if(listener!=null) listener.onMoveComplete(source, destination, b ? MoveState.COMPLETED : MoveState.PARTIAL, this); // Report move completion status
        });
        moveOp.setPriority(Thread.MAX_PRIORITY);
        moveOp.start();
    }

    /**
     * Initiates a file transfer operation from the specified source (be it a single file or an entire folder) to the given destination.
     * @param source Source location to move file(s)/folder(s) from.
     * @param destination Location to move file(s)/folder(s) to.
     */
    public FileMover(File source, File destination){ this(source, destination, DEFAULT_BUFFER_SIZE, true, null, null, null); }

    /**
     * Get the size (in bytes) of the data to be transferred. (Here to allow for tracking of progress)
     * @param f Top file to enumerate from.
     * @param b Value of already enumerated data.
     * @return Enumerated value from file(s)/folder(s)/subfolder(s) plus the original size passed as a parameter.
     */
    private static BigInteger enumerate(File f, BigInteger b){
        if(f.isFile()) return b.add(BigInteger.valueOf(f.length()));
        if(f.isDirectory()) for(File f1 : f.listFiles()) b = enumerate(f1, b);
        return b;
    }

    /**
     * Determines if the move operation is still ongoing.
     * @return True if operation is still alive (still moving data), otherwise false.
     */
    public boolean isAlive(){ return moveOp.isAlive(); }

    /**
     * Total size (in bytes) of data to move.
     * @return Data size in bytes.
     */
    public BigInteger getTotalSize(){ return totalSize; }

    /**
     * Current data transfer progress.
     * @return Amount of transferred bytes.
     */
    public BigInteger getCurrentTransferSize(){ return currentTransferSize; }

    /**
     * Cancel the transfer operation.
     */
    public void cancel(){ cancel = moveOp.isAlive(); } // Only set the value of the process is still alive

    /**
     * Check if operation is still alive but cancelling.
     * @return True if cancelling. False if not cancelling or if successfully cancelled.
     */
    public boolean isCanceling(){ return cancel && moveOp.isAlive(); }

    /**
     * Check if the operation has been successfully cancelled.
     * @return True if process has been stopped due to cancellation. False if process is still alive or a cancellation hasn't been requested.
     */
    public boolean isCanceled(){ return cancel && !moveOp.isAlive(); }

    /**
     * Move any and all files from the source if it's a directory. Just move the file if source is a file.
     * @param source File/folder to move.
     * @param destDir Directory to move source into.
     * @param first Whether or not this is the first move operation of the move process.
     */
    protected void moveAll(File source, File destDir, boolean first)
    {
        if(cancel) return; // Cancel requested: don't initiate more move operations
        File f = first ? destDir : new File(destDir.getAbsolutePath()+File.separatorChar+source.getName());
        if(source.isFile()) moveFile(source, f);
        else if(source.isDirectory()){ // If-statement is here is safeguard in case something goes wrong and we try to move a non-existent file/folder
            if(!f.isDirectory() && !f.mkdir() && handler!=null) handler.onMoveError(source, destDir);
            for(File f1 : source.listFiles()){
                if(cancel) break;
                moveAll(f1, f, false);
            }
        }
    }

    /**
     * Destroy residual directories.
     * @param top Top directory/file to start from.
     * @return True if deletion succeeded, otherwise false.
     */
    protected boolean destroyDirs(File top){
        boolean canDestroyMe = true;
        if(top.isDirectory()) for(File f : top.listFiles()) canDestroyMe = destroyDirs(f) && canDestroyMe; // Must attempt to destroy directory!
        return canDestroyMe && top.delete(); // Short-circuit conditional if we can't delete folder to save time. This will not short-circuit for files, only folders
    }

    /**
     * Move a file from a given source file to a given destination file.
     * @param sourceFile File to move data from.
     * @param destFile File to move data to.
     */
    protected void moveFile(File sourceFile, File destFile)
    {
        InputStream read = null;
        OutputStream write = null;
        boolean error = false;
        try{
            { // boolean isn't needed if we get past this check, so we remove it from the local variable array after the check
                boolean b;
                // ---- Error Handling ----
                if ((b=destFile.isFile() && !destFile.delete()) || !destFile.createNewFile()) { // Make sure we actually have a file to write to
                    if (handler != null) handler.onMoveError(sourceFile, destFile);
                    throw new Exception(b?"Can't delete existing file: " + destFile.getAbsolutePath():"Can't create file: " + destFile.getAbsolutePath());
                }
            }

            // ---- Prepare move operation ----
            read = new FileInputStream(sourceFile);
            write = new FileOutputStream(destFile);
            byte[] dBuf = new byte[bufferSize];
            int readLen;

            // ---- Move data ----
            while(read.available()>0){
                readLen = read.read(dBuf);
                write.write(dBuf, 0, readLen);
                currentTransferSize = currentTransferSize.add(BigInteger.valueOf(readLen));
                if(cancel) throw new Exception("Move operation from \""+sourceFile.getAbsolutePath()+"\" to \""+destFile.getAbsolutePath()+"\" was canceled!"); // Handle cancel
            }
        }catch(Throwable t){ error = true; t.printStackTrace(); if(!cancel && handler!=null) handler.onMoveError(sourceFile, destFile); } // Don't consider cancellation an error
        finally {
            if(read!=null) try { read.close(); } catch (IOException e) { e.printStackTrace(); } // Ultimate errors are happening if this is thrown
            if(write!=null) try { write.close(); } catch (IOException e) { e.printStackTrace(); } // Ultimate errors are happening if this is thrown
            if(!cancel && !sourceFile.delete() && verbose) System.err.println("Can't delete source file: "+sourceFile.getAbsolutePath()); // Handle regular move event
            if(cancel && !destFile.delete() && verbose) System.err.println("Can't delete destination file: "+destFile.getAbsolutePath()); // Handle cancel event
            if(perFile != null)
                perFile.onMoveComplete(
                    sourceFile,
                    destFile, read == null || write == null || (error && !cancel) ? MoveState.FAILED : cancel ? MoveState.PARTIAL : MoveState.COMPLETED,
                    this
                );
        }
    }

    /**
     * Callback interface whenever an error with moving is encountered.
     */
    public interface FileMoveErrorHandler { void onMoveError(File sourceFile, File destinationFile); }

    /**
     * Callback for when a move operation is completed.
     */
    public interface OnMoveCompletedListener { void onMoveComplete(File source, File destination, MoveState success, FileMover instance); }

    /**
     * Move state discriminator. Used to determine the nature of a finished move operation.
     */
    public enum MoveState{
        /**
         * Move was a complete success. No files remain in the original location.
         */
        COMPLETED,
        /**
         * Partial success. Certain files may not have been moved or may still reside in the original location.
         */
        PARTIAL,
        /**
         * Critical failure. No files were moved.
         */
        FAILED
    }
}
