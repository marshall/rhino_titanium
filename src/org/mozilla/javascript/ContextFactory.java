/* -*- Mode: java; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 4 -*- */

/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is a factory class for Context creation.
 *
 * The Initial Developer of the Original Code is
 * RUnit Software AS.
 * Portions created by the Initial Developer are Copyright (C) 2004
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s): Igor Bukanov, igor@fastmail.fm
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * ***** END LICENSE BLOCK ***** */

// API class

package org.mozilla.javascript;

/**
 * Factory class that Rhino runtime use to create new {@link Context}
 * instances or to notify about Context execution.
 * <p>
 * When Rhino runtime needs to create new {@link Context} instance during
 * execution of {@link Context#enter()} or {@link Context}, it will call
 * {@link #makeContext()} of the current global ContextFactory.
 * See {@link #getGlobal()} and {@link #initGlobal(ContextFactory)}.
 * <p>
 * It is also possible to use explicit ContextFactory instances for Context
 * creation. This is useful to have a set of independent Rhino runtime
 * instances under single JVM. See {@link #call(ContextAction)}.
 * <p>
 * The following example demonstrates Context customization to terminate
 * scripts running more then 10 seconds and to provide better compatibility
 * with JavaScript code using MSIE-specific features.
 * <pre>
 * import org.mozilla.javascript.*;
 *
 * class MyFactory extends ContextFactory
 * {
 *     static {
 *         // Initialize GlobalFactory with custom factory
 *         ContextFactory.initGlobal(new MyFactory());
 *     }
 *
 *     protected Context makeContext()
 *     {
 *         MyContext cx = new MyContext();
 *         // Use pure interpreter mode to allow for
 *         // {@link Context#observeInstructionCount(int)} to work
 *         cx.setOptimizationLevel(-1);
 *         // Make Rhino runtime to call MyContext.observeInstructionCount(int)
 *         // each 10000 bytecode instructions
 *         cx.setInstructionObserverThreshold(10000);
 *         return cx;
 *     }
 * }
 *
 * class MyContext extends Context
 * {
 *     private long creationTime = System.currentTimeMillis();
 *
 *     // Override {@link Context#observeInstructionCount(int)}
 *     protected void observeInstructionCount(int instructionCount)
 *     {
 *         long currentTime = System.currentTimeMillis();
 *         if (currentTime - creationTime > 10000) {
 *             // More then 10 seconds from Context creation time:
 *             // it is time to stop the script.
 *             // Throw Error instance to ensure that script will never
 *             // get control back through catch or finally.
 *             throw new Error();
 *         }
 *     }
 *
 *     // Override {@link Context#hasFeature(int)}
 *     public boolean hasFeature(int featureIndex)
 *     {
 *         // Turn on maximim compatibility with MSIE scripts
 *         switch (featureIndex) {
 *             case Context.FEATURE_NON_ECMA_GET_YEAR:
 *                 return true;
 *
 *             case Context.FEATURE_MEMBER_EXPR_AS_FUNCTION_NAME:
 *                 return true;
 *
 *             case Context.FEATURE_RESERVED_KEYWORD_AS_IDENTIFIER:
 *                 return true;
 *
 *             case Context.FEATURE_PARENT_PROTO_PROPRTIES:
 *                 return false;
 *         }
 *         return super.hasFeature(featureIndex);
 *     }
 * }
 * </pre>
 */

public class ContextFactory
{
    private static volatile boolean hasCustomGlobal;
    private static ContextFactory global = new ContextFactory();

    private volatile boolean sealed;

    private final Object listenersLock = new Object();
    private volatile Object listeners;
    private boolean disabledListening;

    /**
     * Listener of {@link Context} creation and release events.
     */
    public interface Listener
    {
        /**
         * Notify about newly created {@link Context} object.
         */
        public void contextCreated(Context cx);

        /**
         * Notify that the specified {@link Context} instance is no longer
         * associated with the current thread.
         */
        public void contextReleased(Context cx);
    }

    /**
     * Get default ContextFactory.
     */
    public static ContextFactory getGlobal()
    {
        return global;
    }

    /**
     * Initialize default ContextFactory. The method can only be called once.
     */
    public static void initGlobal(ContextFactory factory)
    {
        if (factory == null) {
            throw new IllegalArgumentException();
        }
        if (hasCustomGlobal) {
            throw new IllegalStateException();
        }
        hasCustomGlobal = true;
        global = factory;
    }

    /**
     * Create new {@link Context} instance to be associated with the current
     * thread.
     * This is a callback method used by Rhino to create {@link Context}
     * instance when it is necessary to associate one with the current
     * execution thread. <tt>makeContext()</tt> is allowed to call
     * {@link Context#seal(Object)} on the result to prevent
     * {@link Context} changes by hostile scripts or applets.
     */
    protected Context makeContext()
    {
        return new Context();
    }

    protected void onContextCreated(Context cx)
    {
        Object listeners = this.listeners;
        for (int i = 0; ; ++i) {
            Listener l = (Listener)Kit.getListener(listeners, i);
            if (l == null)
                break;
            l.contextCreated(cx);
        }
    }

    protected void onContextReleased(Context cx)
    {
        Object listeners = this.listeners;
        for (int i = 0; ; ++i) {
            Listener l = (Listener)Kit.getListener(listeners, i);
            if (l == null)
                break;
            l.contextReleased(cx);
        }
    }

    public final void addListener(Listener listener)
    {
        checkNotSealed();
        synchronized (listenersLock) {
            if (disabledListening) {
                throw new IllegalStateException();
            }
            listeners = Kit.addListener(listeners, listener);
        }
    }

    public final void removeListener(Listener listener)
    {
        checkNotSealed();
        synchronized (listenersLock) {
            if (disabledListening) {
                throw new IllegalStateException();
            }
            listeners = Kit.removeListener(listeners, listener);
        }
    }

    /**
     * The method is used only to imlement
     * Context.disableStaticContextListening()
     */
    final void disableContextListening()
    {
        checkNotSealed();
        synchronized (listenersLock) {
            disabledListening = true;
            listeners = null;
        }
    }

    /**
     * Checks if this is a sealed ContextFactory.
     * @see #seal()
     */
    public final boolean isSealed()
    {
        return sealed;
    }

    /**
     * Seal this ContextFactory so any attempt to modify it like to add or
     * remove its listeners will throw an exception.
     * @see #isSealed()
     */
    public final void seal()
    {
        checkNotSealed();
        sealed = true;
    }

    protected final void checkNotSealed()
    {
        if (sealed) throw new IllegalStateException();
    }

    /**
     * Call {@link ContextAction#run(Context cx)}
     * using the {@link Context} instance associated with the current thread.
     * If no Context is associated with the thread, then
     * {@link #makeContext()} will be called to construct
     * new Context instance. The instance will be temporary associated
     * with the thread during call to {@link ContextAction#run(Context)}.
     *
     * @see ContextFactory#call(ContextAction)
     * @see Context#call(ContextFactory factory, Callable callable,
     *                   Scriptable scope, Scriptable thisObj,
     *                   Object[] args)
     */
    public final Object call(ContextAction action)
    {
        return Context.call(this, action);
    }
}

