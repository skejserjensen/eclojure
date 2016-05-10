// eClojure
/**
 * Copyright (c) Daniel Rune Jensen, Thomas Stig Jacobsen and
 * Søren Kejser Jensen. All rights reserved.
 * The use and distribution terms for this software are covered by the Eclipse
 * Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php) which
 * can be found in the file epl-v10.html at the root of this distribution. By
 * using this software in any fashion, you are agreeing to be bound by the
 * terms of this license.
 * You must not remove this notice, or any other, from this software.
 */

package clojure.lang;

import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;

/**
 * Event manager for generic and transactional events
 */
public class EventManager {
    /**
     * Stores the current context set by the thread calling notify
     */
    private static ThreadLocal<Object> context = new ThreadLocal<Object>();

    /**
     * Stores global events that is shared by all threads despite what thread crated them
     */
    private final static HashMap<Keyword, ArrayList<EventFn>> globalEvents = new HashMap<Keyword, ArrayList<EventFn>>();

    /**
     * Stores local events that only is available for the thread that created them
     */
    private final static ThreadLocal<HashMap<Keyword, ArrayList<EventFn>>> threadlocalEvents =
        new ThreadLocal<HashMap<Keyword, ArrayList<EventFn>>>() {
            @Override
            protected HashMap<Keyword, ArrayList<EventFn>> initialValue() {
                return new HashMap<Keyword, ArrayList<EventFn>>();
            }
        };

    /**
     * Stores local events that only is available for the thread that created them
     */
    public static final Keyword DISMISSALL = Keyword.intern("all");
    public static final Keyword DISMISSLOCAL = Keyword.intern("local");
    public static final Keyword DISMISSGLOBAL = Keyword.intern("global");

    /**
     * Listen for a specific event to be notified given a Keyword during a transaction
     *
     * @param key            The keyword to identify the event
     * @param fn             Function to be executed
     * @param args           Arguments for the function or null for function without arguments
     * @param deleteAfterRun Whether or not the delete the event after it was run once
     *
     * @return               Will return a EventFn object to be used for eventual removal
     */
    public static EventFn stmListen(Keyword key, IFn fn, ISeq args, boolean deleteAfterRun) {
        // Throws exception if no transaction is running
        LockingTransaction transaction = LockingTransaction.getEx();

        // Events constructed inside a transaction is always local to the transaction only
        Map<Keyword, ArrayList<EventFn>> eventListeners = transaction.getEventListeners();
        if ( ! eventListeners.containsKey(key)) {
            eventListeners.put(key, new ArrayList<EventFn>());
        }

        EventFn eventFn = new EventFn(fn, args, deleteAfterRun);
        eventListeners.get(key).add(eventFn);

        return eventFn;
    }

    /**
     * Notifies all event listeners waiting for the keyword given as key during a transaction
     *
     * @param key The key indicating which events to notify
     * @param context Data given from notify
     */
    public static void stmNotify(Keyword key, Object context) {
        // Throws exception if no transaction is running
        LockingTransaction transaction = LockingTransaction.getEx();
        Map<Keyword, ArrayList<EventFn>> eventListeners = transaction.getEventListeners();
        EventManager.runEvents(key, eventListeners, context);
    }

    /**
     * Listen for a specific event to be notified given a Keyword
     *
     * @param key            The keyword to identify the event
     * @param fn             Function to be executed
     * @param args           Arguments for the function or null for function without arguments
     * @param threadLocal    Whether or not the event should be thread local or not
     * @param deleteAfterRun Whether or not the delete the event after it was run once
     *
     * @return               Will return a EventFn object to be used for eventual removal
     */
    public static EventFn listen(Keyword key, IFn fn, ISeq args, boolean threadLocal, boolean deleteAfterRun) {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("Listen is not allowed in a transaction, use stmListen");
        }

        // Determines if the thunk should be added as as global or thread local event listener
        Map<Keyword, ArrayList<EventFn>> eventMap =
            (threadLocal) ? EventManager.threadlocalEvents.get() : EventManager.globalEvents;

        // Create the EventFn for the given fn and args, then add the EventFn to the list
        EventFn listenerEventFn = new EventFn(fn, args, deleteAfterRun);

        // Check if the HashMap has a key for the given Keyword
        synchronized (eventMap) {
            if ( ! eventMap.containsKey(key)) {
                eventMap.put(key, new ArrayList<EventFn>());
            }

            eventMap.get(key).add(listenerEventFn);
        }

        // Return the EventFn for easy future removal for the developer
        return listenerEventFn;
    }

    /**
     * Notifies all event listeners waiting for the keyword given as key
     *
     * @param key The key indicating which events to notify
     */
    public static void notify(Keyword key, Object context) {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("Notify is not allowed in a transaction, use stmNotify");
        }
        synchronized (EventManager.globalEvents) {
            EventManager.runEvents(key, EventManager.globalEvents, context);
        }
        EventManager.runEvents(key, EventManager.threadlocalEvents.get(), context);
    }

    /**
     * Dismiss an EventFn from an event keyword
     *
     * @param key         The key from which to dismiss the EventFn
     * @param eventFn     The EventFn to dismiss from the given key
     * @param dismissFrom A keyword indicating if the event should be dismissed from local, global or all
     */
    public static void dismiss(Keyword key, EventFn eventFn, Keyword dismissFrom) {
        if (LockingTransaction.isRunning()) {
            throw new IllegalStateException("Dismiss is not allowed in a transaction, events are dismissed with the transaction");
        }

        if (dismissFrom != DISMISSALL && dismissFrom != DISMISSGLOBAL && dismissFrom != DISMISSLOCAL) {
            throw new IllegalArgumentException("The dismissFrom keyword must be either :all, :global or :local");
        }

        if (dismissFrom == DISMISSALL || dismissFrom == DISMISSGLOBAL) {
            synchronized (EventManager.globalEvents) {
                if (EventManager.globalEvents.containsKey(key)) {
                    EventManager.globalEvents.get(key).remove(eventFn);
                }
            }
        }

        if (dismissFrom == DISMISSALL || dismissFrom == DISMISSLOCAL) {
            if (EventManager.threadlocalEvents.get().containsKey(key)) {
                EventManager.threadlocalEvents.get().get(key).remove(eventFn);
            }
        }
    }

    /**
     * Returns the context set by the currently running event
     *
     * @return The current context set by the event, null if none is set or no event is running
     */
    static public Object getContext() {
        return EventManager.context.get();
    }

    /**
     * Run all events found for the given key in events
     *
     * @param key     The key to look for in events
     * @param events  The events to look through
     * @param context Data given from notify
     */
    static void runEvents(Keyword key, Map<Keyword, ArrayList<EventFn>> events, Object context) {
        // Checks if there are any events
        if (events.containsKey(key)) {
            // A delete list is used to prevent concurrent modification exceptions
            ArrayList<EventFn> toDeleteAfterRun = new ArrayList<EventFn>();

            // Set context
            EventManager.context.set(context);

            for (EventFn fn : events.get(key)) {
                fn.run();

                if (fn.deleteAfterRun()) {
                    toDeleteAfterRun.add(fn);
                }
            }
            // Prevents the context from leaking outside the scope of the event
            EventManager.context.set(null);
            events.get(key).removeAll(toDeleteAfterRun);
        }
    }
}
