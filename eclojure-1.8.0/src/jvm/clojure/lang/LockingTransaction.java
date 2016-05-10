// eClojure
/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 26, 2007 */

package clojure.lang;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

@SuppressWarnings({"SynchronizeOnNonFinalField"})
public class LockingTransaction{

    public static final int RETRY_LIMIT = 10000;
    public static final int LOCK_WAIT_MSECS = 100;
    public static final long BARGE_WAIT_NANOS = 10 * 1000000;
    //public static int COMMUTE_RETRY_LIMIT = 10;

    static final int RUNNING = 0;
    static final int COMMITTING = 1;
    static final int RETRY = 2;
    static final int KILLED = 3;
    static final int COMMITTED = 4;

	// Event handle keywords, public to allow use by macros, ensures change are reflected in both places
	public static final Keyword ONABORTKEYWORD = Keyword.intern("on-abort");
	public static final Keyword ONCOMMITKEYWORD = Keyword.intern("on-commit");
	public static final Keyword AFTERCOMMITKEYWORD = Keyword.intern("after-commit");

    final static ThreadLocal<LockingTransaction> transaction = new ThreadLocal<LockingTransaction>();

    static class RetryEx extends Error{
    }
    static class TCRetryEx extends RetryEx{
    }

    static class AbortException extends Exception{
    }
    static class STMEventException extends RuntimeException{
        public STMEventException(String message) {
            super(message);
        }
    }

    public static class Info{
        final AtomicInteger status;
        final long startPoint;
        final CountDownLatch latch;


        public Info(int status, long startPoint){
            this.status = new AtomicInteger(status);
            this.startPoint = startPoint;
            this.latch = new CountDownLatch(1);
        }

        public boolean running(){
            int s = status.get();
            return s == RUNNING || s == COMMITTING;
        }
    }

    static class CFn{
        final IFn fn;
        final ISeq args;

        public CFn(IFn fn, ISeq args){
            this.fn = fn;
            this.args = args;
        }
    }
    //total order on transactions
    //transactions will consume a point for init, for each retry, and on commit if writing
    final private static AtomicLong lastPoint = new AtomicLong();

    void getReadPoint(){
        readPoint = lastPoint.incrementAndGet();
    }

    long getCommitPoint(){
        return lastPoint.incrementAndGet();
    }

    void stop(int status){
        if(info != null)
        {
            synchronized(info)
            {
                info.status.set(status);
                info.latch.countDown();
            }
            info = null;
            vals.clear();
            sets.clear();
            commutes.clear();
            //actions.clear();
        }
    }


    Info info;
    long readPoint;
    long startPoint;
    long startTime;
    final RetryEx retryex = new RetryEx();
    final RetryEx tcRetryex = new TCRetryEx();
    final ArrayList<Agent.Action> actions = new ArrayList<Agent.Action>();
    final HashMap<Ref, Object> vals = new HashMap<Ref, Object>();
    final HashSet<Ref> sets = new HashSet<Ref>();
    final HashSet<Ref> gets = new HashSet<Ref>();
    final TreeMap<Ref, ArrayList<CFn>> commutes = new TreeMap<Ref, ArrayList<CFn>>();
    final HashSet<Ref> ensures = new HashSet<Ref>();   //all hold readLock

	private final HashMap<Keyword, ArrayList<EventFn>> eventListeners = new HashMap<Keyword, ArrayList<EventFn>>();

	private boolean orElseRunning = false;
    private STMBlockingBehavior blockingBehavior = null;
    private final static Collection<STMBlockingBehavior> blockingBehaviors =
        Collections.newSetFromMap(new ConcurrentHashMap<STMBlockingBehavior, Boolean>());


    void tryWriteLock(Ref ref){
        try
        {
            if(!ref.lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS))
                throw retryex;
        }
        catch(InterruptedException e)
        {
            throw retryex;
        }
    }

    //returns the most recent val
    Object lock(Ref ref){
        //can't upgrade readLock, so release it
        releaseIfEnsured(ref);

        boolean unlocked = true;
        try
        {
            tryWriteLock(ref);
            unlocked = false;

            if(ref.tvals != null && ref.tvals.point > readPoint)
                throw retryex;
            Info refinfo = ref.tinfo;

            //write lock conflict
            if(refinfo != null && refinfo != info && refinfo.running())
            {
                if(!barge(refinfo))
                {
                    ref.lock.writeLock().unlock();
                    unlocked = true;
                    return blockAndBail(refinfo);
                }
            }
            ref.tinfo = info;
            return ref.tvals == null ? null : ref.tvals.val;
        }
        finally
        {
            if(!unlocked)
                ref.lock.writeLock().unlock();
        }
    }

    private Object blockAndBail(Info refinfo){
        // Disables block and bail when doing an or else block
        if(this.orElseRunning) {
            throw retryex;
        }

        //Executes on-abort events before stopping and blocking
        executeOnAbortEvents();

        //stop prior to blocking
        stop(RETRY);
        try
        {
            refinfo.latch.await(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS);
        }
        catch(InterruptedException e)
        {
            //ignore
        }
        throw retryex;
    }

    private void releaseIfEnsured(Ref ref){
        if(ensures.contains(ref))
        {
            ensures.remove(ref);
            ref.lock.readLock().unlock();
        }
    }

    void abort() throws AbortException{
        //On-Abort events are executed here and not in exception clause due to stop
        executeOnAbortEvents();
        stop(KILLED);
        throw new AbortException();
    }

    private boolean bargeTimeElapsed(){
        return System.nanoTime() - startTime > BARGE_WAIT_NANOS;
    }

    private boolean barge(Info refinfo){
        boolean barged = false;
        //if this transaction is older
        //  try to abort the other
        if(bargeTimeElapsed() && startPoint < refinfo.startPoint)
        {
            barged = refinfo.status.compareAndSet(RUNNING, KILLED);
            if(barged)
                refinfo.latch.countDown();
        }
        return barged;
    }

    static LockingTransaction getEx(){
        LockingTransaction t = transaction.get();
        if(t == null || t.info == null)
            throw new IllegalStateException("No transaction running");
        return t;
    }

    static public boolean isRunning(){
        return getRunning() != null;
    }

    static LockingTransaction getRunning(){
        LockingTransaction t = transaction.get();
        if(t == null || t.info == null)
            return null;
        return t;
    }

    static public Object runInTransaction(Callable fn) throws Exception{
        LockingTransaction t = transaction.get();
        Object ret;
        if(t == null) {
            transaction.set(t = new LockingTransaction());
            try {
                ret = t.run(fn);
            } finally {
                transaction.remove();
            }
        } else {
            if(t.info != null) {
                ret = fn.call();
            } else {
                ret = t.run(fn);
            }
        }

        return ret;
    }

    static class Notify{
        final public Ref ref;
        final public Object oldval;
        final public Object newval;

        Notify(Ref ref, Object oldval, Object newval){
            this.ref = ref;
            this.oldval = oldval;
            this.newval = newval;
        }
    }

    Object run(Callable fn) throws Exception{
        boolean done = false;
        Object ret = null;
        ArrayList<Ref> locked = new ArrayList<Ref>();
        ArrayList<Notify> notify = new ArrayList<Notify>();

        for(int i = 0; !done && i < RETRY_LIMIT; i++)
        {
            //Blocks on any set blocking behaviors and clears the set of read refs
            if (this.blockingBehavior != null) {
                this.blockingBehavior.await();
                LockingTransaction.blockingBehaviors.remove(this.blockingBehavior);
                this.blockingBehavior = null;
            }
            gets.clear();

            try
            {
                getReadPoint();
                if(i == 0)
                {
                    startPoint = readPoint;
                    startTime = System.nanoTime();
                }
                info = new Info(RUNNING, startPoint);
                ret = fn.call();
                //make sure no one has killed us before this point, and can't from now on
                if(info.status.compareAndSet(RUNNING, COMMITTING))
                {
                    for(Map.Entry<Ref, ArrayList<CFn>> e : commutes.entrySet())
                    {
                        Ref ref = e.getKey();
                        if(sets.contains(ref)) continue;

                        boolean wasEnsured = ensures.contains(ref);
                        //can't upgrade readLock, so release it
                        releaseIfEnsured(ref);
                        tryWriteLock(ref);
                        locked.add(ref);
                        if(wasEnsured && ref.tvals != null && ref.tvals.point > readPoint)
                            throw retryex;

                        Info refinfo = ref.tinfo;
                        if(refinfo != null && refinfo != info && refinfo.running())
                        {
                            if(!barge(refinfo))
                                throw retryex;
                        }
                        Object val = ref.tvals == null ? null : ref.tvals.val;
                        vals.put(ref, val);
                        for(CFn f : e.getValue())
                        {
                            vals.put(ref, f.fn.applyTo(RT.cons(vals.get(ref), f.args)));
                        }
                    }
                    for(Ref ref : sets)
                    {
                        tryWriteLock(ref);
                        locked.add(ref);
                    }

                    //validate and enqueue notifications
                    for(Map.Entry<Ref, Object> e : vals.entrySet())
                    {
                        Ref ref = e.getKey();
                        ref.validate(ref.getValidator(), e.getValue());
                    }

					//Notify all listeners for "on-commit" event
					PersistentHashSet persistentSets = PersistentHashSet.create(RT.seq(this.vals.keySet()));
                    try {
                        EventManager.runEvents(LockingTransaction.ONCOMMITKEYWORD, this.eventListeners, persistentSets);
                    } catch(RetryEx ex) {
                        throw new STMEventException("stm transaction restarted doing on-commit event");
                    }

                    //at this point, all values computed, all refs to be written locked
                    //no more client code to be called
                    long commitPoint = getCommitPoint();
                    for(Map.Entry<Ref, Object> e : vals.entrySet())
                    {
                        Ref ref = e.getKey();
                        Object oldval = ref.tvals == null ? null : ref.tvals.val;
                        Object newval = e.getValue();
                        int hcount = ref.histCount();

                        if(ref.tvals == null)
                        {
                            ref.tvals = new Ref.TVal(newval, commitPoint);
                        }
                        else if((ref.faults.get() > 0 && hcount < ref.maxHistory)
                                || hcount < ref.minHistory)
                        {
                            ref.tvals = new Ref.TVal(newval, commitPoint, ref.tvals);
                            ref.faults.set(0);
                        }
                        else
                        {
                            ref.tvals = ref.tvals.next;
                            ref.tvals.val = newval;
                            ref.tvals.point = commitPoint;
                        }
                        if(ref.getWatches().count() > 0)
                            notify.add(new Notify(ref, oldval, newval));
                    }

                    done = true;
                    info.status.set(COMMITTED);
                }
            } catch(RetryEx ex) {
				// Ignore the exception so we retry rather than fall out
                executeOnAbortEvents();
			} catch(AbortException ae) {
                // We want to terminate the transaction but have nothing to return,
                // on-abort events are executed by stop before it throws this exception
                return null;
			} catch(Exception exception) {
                executeOnAbortEvents();
                throw exception;
            }
            finally
            {
                for(int k = locked.size() - 1; k >= 0; --k)
                {
                    locked.get(k).lock.writeLock().unlock();
                }
                locked.clear();
                for(Ref r : ensures)
                {
                    r.lock.readLock().unlock();
                }
                ensures.clear();
                stop(done ? COMMITTED : RETRY);
                try
                {
                    if(done) //re-dispatch out of transaction
                    {
                        for(Notify n : notify)
                        {
                            n.ref.notifyWatches(n.oldval, n.newval);
                        }
                        for(Agent.Action action : actions)
                        {
                            Agent.dispatchAction(action);
                        }
                        for (STMBlockingBehavior blockingBehavior : LockingTransaction.blockingBehaviors)
                        {
                            blockingBehavior.handleChanged();
                        }
                        EventManager.runEvents(LockingTransaction.AFTERCOMMITKEYWORD, this.eventListeners, null);
                    }
                }
                finally
                {
                    notify.clear();
                    actions.clear();
					eventListeners.clear();
                }
            }
        }
        if(!done)
            throw Util.runtimeException("Transaction failed after reaching retry limit");
        return ret;
    }

    public void enqueue(Agent.Action action){
        actions.add(action);
    }

	HashMap<Keyword, ArrayList<EventFn>> getEventListeners() {
		return this.eventListeners;
	}

    Object doGet(Ref ref){
        if(!info.running())
            throw retryex;
        gets.add(ref);
        if(vals.containsKey(ref))
            return vals.get(ref);
        try
        {
            ref.lock.readLock().lock();
            if(ref.tvals == null)
                throw new IllegalStateException(ref.toString() + " is unbound.");
            Ref.TVal ver = ref.tvals;
            do
            {
                if(ver.point <= readPoint)
                    return ver.val;
            } while((ver = ver.prior) != ref.tvals);
        }
        finally
        {
            ref.lock.readLock().unlock();
        }
        //no version of val precedes the read point
        ref.faults.incrementAndGet();
        throw retryex;

    }

    Object doSet(Ref ref, Object val){
        if(!info.running())
            throw retryex;
        if(commutes.containsKey(ref))
            throw new IllegalStateException("Can't set after commute");
        if(!sets.contains(ref))
        {
            lock(ref);
            sets.add(ref);
        }
        vals.put(ref, val);
        return val;
    }

    void doEnsure(Ref ref){
        if(!info.running())
            throw retryex;
        if(ensures.contains(ref))
            return;
        ref.lock.readLock().lock();

        //someone completed a write after our snapshot
        if(ref.tvals != null && ref.tvals.point > readPoint) {
            ref.lock.readLock().unlock();
            throw retryex;
        }

        Info refinfo = ref.tinfo;

        //writer exists
        if(refinfo != null && refinfo.running())
        {
            ref.lock.readLock().unlock();

            if(refinfo != info) //not us, ensure is doomed
            {
                blockAndBail(refinfo);
            }
        }
        else
            ensures.add(ref);
    }

    Object doCommute(Ref ref, IFn fn, ISeq args) {
        if(!info.running())
            throw retryex;
        if(!vals.containsKey(ref))
        {
            Object val = null;
            try
            {
                ref.lock.readLock().lock();
                val = ref.tvals == null ? null : ref.tvals.val;
            }
            finally
            {
                ref.lock.readLock().unlock();
            }
            vals.put(ref, val);
        }
        ArrayList<CFn> fns = commutes.get(ref);
        if(fns == null)
            commutes.put(ref, fns = new ArrayList<CFn>());
        fns.add(new CFn(fn, args));
        Object ret = fn.applyTo(RT.cons(vals.get(ref), args));
        vals.put(ref, ret);
        return ret;
    }

    void doBlocking(HashSet<Ref> refs, IFn fn, ISeq args, boolean blockOnAll) throws InterruptedException, RetryEx {
        if ( ! info.running()) {
            throw retryex;
        }

        if (refs == null) {
            refs = new HashSet<Ref>();
            refs.addAll(this.gets);
        }

		if (refs.isEmpty()) {
			throw new IllegalArgumentException("The set of Refs cannot be empty");
		}

        if (blockOnAll) {
			if (fn != null) {
				this.blockingBehavior = new STMBlockingBehaviorFnAll(refs, fn, args, this.readPoint);
			} else {
				this.blockingBehavior = new STMBlockingBehaviorAll(refs, this.readPoint);
			}
        } else {
			if (fn != null) {
				this.blockingBehavior = new STMBlockingBehaviorFnAny(refs, fn, args, this.readPoint);
			} else {
                this.blockingBehavior = new STMBlockingBehaviorAny(refs, this.readPoint);
			}
        }
        LockingTransaction.blockingBehaviors.add(this.blockingBehavior);
        //Use of tcRetryex allows code to differentiate between a retry/retry-all retry and a normal retry
        throw tcRetryex;
    }

    Object doOrElse(boolean orElseOnRetryEx, ArrayList<IFn> fns) {
        if ( ! info.running()) {
            throw retryex;
        }
        this.orElseRunning = true;

        //Checks if or-else should run the next function only for retry/retry-all or all retryex
        if(orElseOnRetryEx) {
            for (IFn fn : fns) {
                try {
                    return fn.invoke();
                } catch (RetryEx ex) {
                    // We ignore the exception to allow the next function to execute
                }
            }
        } else {
            for (IFn fn : fns) {
                try {
                    return fn.invoke();
                } catch (TCRetryEx ex) {
                    // We ignore the exception to allow the next function to execute
                }
            }
        }
		this.orElseRunning = false;
        throw tcRetryex;
    }

    void executeOnAbortEvents() {
        //BlockAndBail stops the transaction before it throws an retryex exception,
        //so it needs to executes the necessary events as stopping releases ownership of refs
        if(info == null) {
            return;
        }

        synchronized(info) {
            info.status.set(COMMITTING);
        }
        try {
            EventManager.runEvents(LockingTransaction.ONABORTKEYWORD, this.eventListeners, null);
        } catch(RetryEx ex) {
            throw new STMEventException("stm transaction restarted doing on-abort event");
        }
    }
}
