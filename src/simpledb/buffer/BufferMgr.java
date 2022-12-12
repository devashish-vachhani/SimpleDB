package simpledb.buffer;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import javafx.util.Pair;

import simpledb.file.*;
import simpledb.log.LogMgr;


public class BufferMgr {
   private int numAvailable;
   private int bufferPoolSize;
   private static final long MAX_TIME = 10000; // 10 seconds
   public static HashMap<BlockId,Buffer> bufferPoolMap;
   private static PriorityQueue<Pair<Integer, Buffer>> lrm;
   private FileMgr fm;
   private LogMgr lm;
   
   /**
    * Creates a buffer manager having the specified number 
    * of buffer slots.
    * This constructor depends on a {@link FileMgr} and
    * {@link simpledb.log.LogMgr LogMgr} object.
    * @param numbuffs the number of buffer slots to allocate
    */
   public BufferMgr(FileMgr fm, LogMgr lm, int numbuffs) {
	  bufferPoolSize = numbuffs;
      numAvailable = numbuffs;
      bufferPoolMap = new HashMap<BlockId,Buffer>();
      lrm = new PriorityQueue<Pair<Integer, Buffer>>(Comparator.comparing(Pair::getKey));
      this.fm = fm;
      this.lm = lm;
   }
   
   FileMgr getFm() {
	   return fm;
   }
   
   LogMgr getLm() {
	   return lm;
   }
   
   /**
    * Returns the number of available (i.e. unpinned) buffers.
    * @return the number of available buffers
    */
   public synchronized int available() {
      return numAvailable;
   }
   
   /**
    * Flushes the dirty buffers modified by the specified transaction.
    * @param txnum the transaction's id number
    */
   public synchronized void flushAll(int txnum) {
	   Iterator<Entry<BlockId,Buffer>> iter = bufferPoolMap.entrySet().iterator();
	   while(iter.hasNext()){
		   Entry<BlockId,Buffer> entry = iter.next();
		   Buffer buff = entry.getValue();
		   if (buff.modifyingTx() == txnum)
			   buff.flush();
	   }
   }
   
   
   /**
    * Unpins the specified data buffer. If its pin count
    * goes to zero, then notify any waiting threads.
    * @param buff the buffer to be unpinned
    */
   public synchronized void unpin(Buffer buff) {
      buff.unpin();
      if (!buff.isPinned()) {
         numAvailable++;
         notifyAll();
         if (buff.getLsn() != -1) lrm.add(new Pair<>(buff.getLsn(), buff));
      }
   }
   
   /**
    * Pins a buffer to the specified block, potentially
    * waiting until a buffer becomes available.
    * If no buffer becomes available within a fixed 
    * time period, then a {@link BufferAbortException} is thrown.
    * @param blk a reference to a disk block
    * @return the buffer pinned to that block
    */
   public synchronized Buffer pin(BlockId blk) {
      try {
         long timestamp = System.currentTimeMillis();
         Buffer buff = tryToPin(blk);
         while (buff == null && !waitingTooLong(timestamp)) {
            wait(MAX_TIME);
            buff = tryToPin(blk);
         }
         if (buff == null)
            throw new BufferAbortException();
         
         return buff;
      }
      catch(InterruptedException e) {
         throw new BufferAbortException();
      }
   }  
   
   private boolean waitingTooLong(long starttime) {
      return System.currentTimeMillis() - starttime > MAX_TIME;
   }
   
   /**
    * Tries to pin a buffer to the specified block. 
    * If there is already a buffer assigned to that block
    * then that buffer is used;  
    * otherwise, an un-pinned buffer from the pool is chosen.
    * Returns a null value if there are no available buffers.
    * @param blk a reference to a disk block
    * @return the pinned buffer
    */
   @SuppressWarnings("unchecked")
private Buffer tryToPin(BlockId blk) {
      Buffer buff = findExistingBuffer(blk);
      if (buff == null) {
    	 buff = chooseUnpinnedBuffer();
    	 if (buff == null) 
    		 return null;
    	 bufferPoolMap.remove(buff.block());
    	 lrm.remove(new Pair<>(buff.getLsn(), buff));
    	 buff.assignToBlock(blk);
    	 bufferPoolMap.put(blk, buff);
      }
      if(!buff.isPinned()) {
    	  numAvailable--;
    	  if(buff.getLsn() != -1) lrm.remove(new Pair<>(buff.getLsn(), buff));
      }
      buff.pin();
      return buff;
   }
   
   @SuppressWarnings("unchecked")
private Buffer findExistingBuffer(BlockId blk) {
	   Buffer buff = bufferPoolMap.getOrDefault(blk, null);
	   if(buff == null) return null;
	   else return buff;
   }
   
   private Buffer chooseUnpinnedBuffer() {
	   if(bufferPoolMap.size() < bufferPoolSize){
		   return new Buffer(getFm(), getLm(), bufferPoolMap.size()+1);
	   }
	  else return lrm.poll().getValue();
   }
   
   public static void printStatus() {
	   System.out.println("\n");
	   System.out.println("Allocated Buffers:");
       for (HashMap.Entry<BlockId, Buffer> iter : bufferPoolMap.entrySet()) {
		   String pinned = " Pinned ";
		   Buffer buff = iter.getValue();
		   if (!buff.isPinned()) pinned = " Unpinned ";
		   System.out.println("Buffer ID: " + buff.getId() + " Block ID: " + iter.getKey().toString() + pinned + " lsn: " + buff.getLsn());
	   }
       System.out.print("Unpinned Buffers in LRM order: ");
       if(lrm.isEmpty()) System.out.print("None");
       for(Pair<Integer, Buffer> iter: lrm) {
    	   System.out.print(iter.getValue().getId() + " (lsn"+ iter.getKey() + ") ");
       }
       System.out.println("\n");
   }
}
