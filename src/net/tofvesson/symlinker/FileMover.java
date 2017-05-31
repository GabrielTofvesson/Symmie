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
    protected final BigInteger totalSize;
    protected final int bufferSize;

    public FileMover(File source, File destination, int bufferSize, FileMoveErrorHandler errorHandler){
        handler = errorHandler;
        this.bufferSize = bufferSize < MINIMUM_BUFFER_SIZE ? bufferSize : DEFAULT_BUFFER_SIZE;
        totalSize = enumerate(source, BigInteger.ZERO);
        moveOp = new Thread(()->{
            if(!destination.isDirectory()) if(!destination.mkdirs()){
                if(handler!=null) handler.onMoveError(source, destination);
                System.err.println("Couldn't create necessary directories!");
                return;
            }
            if(totalSize.subtract(BigInteger.valueOf(destination.getUsableSpace())).toString().charAt(0)!='-'){
                if(handler!=null) handler.onMoveError(source, destination);
                System.err.println("Not enough space in destination to accommodate move operation!");
                return;
            }
            moveAll(source, destination, true);
            if(!destroyDirs(source)) System.err.println("Could not delete source directories!");
            System.out.println("Done!");
        });
        moveOp.setPriority(Thread.MAX_PRIORITY);
        moveOp.start();
    }

    private static BigInteger enumerate(File f, BigInteger b){
        if(f.isFile()) return b.add(BigInteger.valueOf(f.length()));
        if(f.isDirectory()) for(File f1 : f.listFiles()) b = enumerate(f1, b);
        return b;
    }

    public boolean isAlive(){ return moveOp.isAlive(); }
    public BigInteger getTotalSize(){ return totalSize; }
    public BigInteger getCurrentTransferSize(){ return currentTransferSize; }
    public void cancel(){ cancel = true; }
    public boolean isCanceling(){ return cancel && moveOp.isAlive(); }
    public boolean isCanceled(){ return cancel && !moveOp.isAlive(); }

    protected void moveAll(File source, File destDir, boolean first)
    {
        if(cancel) return; // Cancel requested: don't initiate more move operations
        File f = first ? destDir : new File(destDir.getAbsolutePath()+File.separatorChar+source.getName());
        if(source.isFile()) moveFile(source, f);
        else if(source.isDirectory()){ // If-statement is here is safeguard in case something goes wrong and we try to move a non-existent file/folder
            if(!f.mkdir() && handler!=null) handler.onMoveError(source, destDir);
            for(File f1 : source.listFiles()){
                if(cancel) break;
                moveAll(f1, f, false);
            }
        }
    }

    protected boolean destroyDirs(File top){
        boolean canDestroyMe = true;
        if(top.isDirectory()) for(File f : top.listFiles()) canDestroyMe = destroyDirs(f) && canDestroyMe; // Must attempt to destroy directory!
        return top.delete() && canDestroyMe;
    }

    protected void moveFile(File sourceFile, File destFile)
    {
        InputStream read = null;
        OutputStream write = null;
        try{
            // ---- Error Handling ----
            if(destFile.isFile() && !destFile.delete()){
                if(handler!=null) handler.onMoveError(sourceFile, destFile);
                throw new Exception("Can't delete existing file: "+destFile.getAbsolutePath());
            }
            if(!destFile.createNewFile()){ // Make sure we actually have a file to write to
                if(handler!=null) handler.onMoveError(sourceFile, destFile);
                throw new Exception("Can't create file: "+destFile.getAbsolutePath());
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
        }catch(Throwable t){ t.printStackTrace(); if(!cancel && handler!=null) handler.onMoveError(sourceFile, destFile); } // Don't consider cancellation an error
        finally {
            if(read!=null) try { read.close(); } catch (IOException e) { e.printStackTrace(); } // Ultimate errors are happening if this is thrown
            if(write!=null) try { write.close(); } catch (IOException e) { e.printStackTrace(); } // Ultimate errors are happening if this is thrown
            if(!cancel && !sourceFile.delete()) System.err.println("Can't delete source file: "+sourceFile.getAbsolutePath()); // Handle regular move event
            if(cancel && !destFile.delete()) System.err.println("Can't delete destination file: "+destFile.getAbsolutePath()); // Handle cancel event
        }
    }

    public interface FileMoveErrorHandler { void onMoveError(File sourceFile, File destinationFile); }
}
