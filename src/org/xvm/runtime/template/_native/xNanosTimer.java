package org.xvm.runtime.template._native;


import java.util.Timer;
import java.util.TimerTask;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.GenericHandle;
import org.xvm.runtime.ServiceContext;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.TypeComposition;
import org.xvm.runtime.Utils;

import org.xvm.runtime.template.LongLong;
import org.xvm.runtime.template.xBaseInt128.LongLongHandle;
import org.xvm.runtime.template.xFunction;
import org.xvm.runtime.template.xFunction.FunctionHandle;
import org.xvm.runtime.template.xFunction.NativeFunctionHandle;
import org.xvm.runtime.template.xService;
import org.xvm.runtime.template.xUInt128;

import org.xvm.util.ListSet;


/**
 * Native implementation of a simple timer (stop-watch) using Java's nanosecond-resolution "System"
 * clock.
 */
public class xNanosTimer
        extends xService
    {
    // -----  constructors -------------------------------------------------------------------------

    public xNanosTimer(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, false);
        }

    @Override
    public void initDeclared()
        {
        s_clzDuration = f_templates.getTemplate("Duration").getCanonicalClass();

        markNativeProperty("elapsed");

        markNativeMethod("start"   , new String[0], null);
        markNativeMethod("stop"    , new String[0], null);
        markNativeMethod("reset"   , new String[0], null);
        markNativeMethod("schedule", new String[]{"Duration", "Timer.Alarm"}, null);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        TimerHandle hTimer = (TimerHandle) hTarget;
        switch (sPropName)
            {
            case "elapsed":
                return frame.assignValue(iReturn, hTimer.elapsedDuration());
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }

    @Override
    public int invokeNativeN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle[] ahArg, int iReturn)
        {
        TimerHandle hTimer = (TimerHandle) hTarget;
        switch (method.getName())
            {
            case "start":
                hTimer.start();
                return Op.R_NEXT;

            case "stop":
                hTimer.stop();
                return Op.R_NEXT;

            case "reset":
                hTimer.reset();
                return Op.R_NEXT;

            case "schedule": // duration, alarm
                {
                if (frame.f_context != hTimer.m_context)
                    {
                    return xFunction.makeAsyncNativeHandle(method).
                        call1(frame, hTarget, ahArg, iReturn);
                    }

                GenericHandle  hDuration = (GenericHandle ) ahArg[0];
                FunctionHandle hAlarm    = (FunctionHandle) ahArg[1];
                FunctionHandle hCancel   = hTimer.schedule(hDuration, hAlarm);
                return frame.assignValue(iReturn, hCancel);
                }
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    @Override
    public ServiceHandle createServiceHandle(ServiceContext context, ClassComposition clz, TypeConstant typeMask)
        {
        TimerHandle hTimer = new TimerHandle(clz.maskAs(typeMask), context);
        context.setService(hTimer);
        return hTimer;
        }


    // ----- ObjectHandle --------------------------------------------------------------------------

    public static class TimerHandle
            extends ServiceHandle
        {
        public TimerHandle(TypeComposition clazz, ServiceContext context)
            {
            super(clazz, context);

            m_cNanosStart = System.nanoTime();
            }

        @Override
        public boolean isIdle()
            {
            if (isRunning())
                {
                synchronized (f_setAlarms)
                    {
                    for (Alarm alarm : f_setAlarms)
                        {
                        if (!alarm.m_fDead)
                            {
                            return false;
                            }
                        }
                    }
                }
            return true;
            }

        // -----  Timer implementation -------------------------------------------------------------

        /**
         * Start the timer, which also starts all alarms.
         */
        public synchronized void start()
            {
            if (m_cNanosStart == 0)
                {
                m_cNanosStart = System.nanoTime();
                }

            synchronized (f_setAlarms)
                {
                for (Alarm alarm : f_setAlarms)
                    {
                    alarm.start();
                    }
                }
            }

        /**
         * @return true if the timer is running (the last call to "start" has not been followed by a call
         *         to "stop")
         */
        public boolean isRunning()
            {
            return m_cNanosStart != 0;
            }

        /**
         * @return the elapsed time, in nano-seconds
         */
        public synchronized long elapsed()
            {
            long cNanosTotal = m_cNanosPrevSum;
            if (m_cNanosStart != 0)
                {
                long cAdd = System.nanoTime() - m_cNanosStart;
                if (cAdd > 0)
                    {
                    cNanosTotal += cAdd;
                    }
                }
            return cNanosTotal;
            }

        /**
         * Create and schedule an alarm to go off if a specified number of nanoseconds.
         *
         * @param hDuration  the duration before triggering the alarm
         * @param hAlarm     the runtime function to call when the alarm triggers
         *
         * @return the new Alarm
         */
        public FunctionHandle schedule(GenericHandle hDuration, FunctionHandle hAlarm)
            {
            // note: the Java Timer uses millisecond scheduling, but we're given scheduling
            // instructions in picoseconds
            LongLongHandle llPicos = (LongLongHandle) hDuration.getField("picoseconds");
            long            cNanos  = Math.max(0, llPicos.getValue().divUnsigned(PICOS_PER_NANO).getLowValue());
            Alarm           alarm   = new Alarm(++s_cAlarms, cNanos, hAlarm);

            return new NativeFunctionHandle((_frame, _ah, _iReturn) ->
                {
                alarm.cancel();
                return Op.R_NEXT;
                });
            }

        /**
         * Stop the timer, which also stops all alarms.
         */
        public synchronized void stop()
            {
            if (m_cNanosStart != 0)
                {
                long cAdd = System.nanoTime() - m_cNanosStart;
                if (cAdd > 0)
                    {
                    m_cNanosPrevSum += cAdd;
                    }

                m_cNanosStart = 0;

                synchronized (f_setAlarms)
                    {
                    for (Alarm alarm : f_setAlarms)
                        {
                        alarm.stop();
                        }
                    }
                }
            }

        /**
         * Reset the timer, which resets the elapsed time and all alarms.
         */
        public synchronized void reset()
            {
            m_cNanosPrevSum = 0;
            if (m_cNanosStart != 0)
                {
                m_cNanosStart = System.nanoTime();
                }

            synchronized (f_setAlarms)
                {
                for (Alarm alarm : f_setAlarms)
                    {
                    alarm.reset();
                    }
                }
            }

        /**
         * @return the elapsed time, as an Ecstasy Duration object
         */
        public GenericHandle elapsedDuration()
            {
            GenericHandle hDuration = new GenericHandle(s_clzDuration);

            LongLong llPicos   = new LongLong(elapsed()).mul(PICOS_PER_NANO_LL);
            hDuration.setField("picoseconds", xUInt128.INSTANCE.makeLongLong(llPicos));
            hDuration.makeImmutable();

            return hDuration;
            }

        public void register(Alarm alarm)
            {
            boolean fRegistered;
            synchronized (f_setAlarms)
                {
                fRegistered = f_setAlarms.add(alarm);
                }
            assert fRegistered;
            }

        public void unregister(Alarm alarm)
            {
            boolean fUnregistered;
            synchronized (f_setAlarms)
                {
                fUnregistered = f_setAlarms.remove(alarm);
                }
            assert fUnregistered;
            }

        // ----- inner class: Alarm --------------------------------------------------------------------

        /**
         * Represents a pending alarm.
         */
        protected class Alarm
            {
            /**
             * Construct and register an alarm, and start it if the timer is running.
             *
             * @param id           a unique id for the alarm
             * @param cNanosDelay  the delay before triggering the alarm
             * @param hFunction    the runtime function to call when the alarm triggers
             */
            public Alarm(int id, long cNanosDelay, FunctionHandle hFunction)
                {
                f_id          = id;
                f_cNanosDelay = cNanosDelay;
                f_hFunction   = hFunction;

                TimerHandle timer = TimerHandle.this;
                timer.register(this);
                if (timer.isRunning())
                    {
                    start();
                    }
                }

            /**
             * Start the alarm maturing on a running timer.
             */
            public synchronized void start()
                {
                if (m_fDead || m_trigger != null)
                    {
                    return;
                    }

                m_cNanosStart = System.nanoTime();
                m_trigger     = new Trigger();
                try
                    {
                    TIMER.schedule(m_trigger, Math.max(1, (f_cNanosDelay - m_cNanosBurnt) / NANOS_PER_MILLI));
                    }
                catch (Exception e)
                    {
                    System.err.println("Exception in xNanosTimer.Alarm.start(): " + e);
                    }
                }

            /**
             * Stop the alarm from maturing.
             */
            public synchronized void stop()
                {
                if (m_fDead)
                    {
                    return;
                    }

                if (m_trigger != null)
                    {
                    m_trigger.cancel();
                    m_trigger = null;

                    long cNanosAdd = System.nanoTime() - m_cNanosStart;
                    if (cNanosAdd > 0)
                        {
                        m_cNanosBurnt += cNanosAdd;
                        }
                    m_cNanosStart = 0;
                    }
                }

            /**
             * Reset the alarm maturity back to its initial state.
             */
            public synchronized void reset()
                {
                if (m_fDead)
                    {
                    return;
                    }

                if (m_trigger != null)
                    {
                    m_trigger.cancel();
                    m_trigger = null;

                    m_cNanosStart = 0;
                    m_cNanosBurnt = 0;

                    if (TimerHandle.this.isRunning())
                        {
                        start();
                        }
                    }
                }

            /**
             * Called when the alarm is triggered.
             */
            public void run()
                {
                synchronized (this)
                    {
                    if (m_fDead)
                        {
                        return;
                        }
                    m_fDead   = true;
                    m_trigger = null;
                    }

                TimerHandle.this.unregister(this);
                TimerHandle.this.m_context.callLater(f_hFunction, Utils.OBJECTS_NONE);
                }

            /**
             * Called when the alarm is canceled.
             */
            public void cancel()
                {
                synchronized (this)
                    {
                    if (m_fDead)
                        {
                        return;
                        }
                    m_fDead = true;

                    Trigger trigger = m_trigger;
                    if (trigger != null)
                        {
                        m_trigger = null;
                        try
                            {
                            trigger.cancel();
                            }
                        catch (Exception e)
                            {
                            System.err.println("Exception in xNanosTimer.Alarm.cancel(): " + e);
                            }
                        }
                    }

                TimerHandle.this.unregister(this);
                }

            @Override
            public int hashCode()
                {
                return f_id;
                }

            @Override
            public boolean equals(Object obj)
                {
                return this == obj;
                }

            /**
             * A TimerTask that is scheduled on the Java timer and used to trigger the alarm.
             */
            protected class Trigger
                    extends TimerTask
                {
                @Override
                public void run()
                    {
                    Alarm.this.run();
                    }
                }

            private final    FunctionHandle f_hFunction;
            private final    int            f_id;
            private final    long           f_cNanosDelay;
            private          long           m_cNanosStart;
            private          long           m_cNanosBurnt;
            private volatile boolean        m_fDead;
            private volatile Trigger        m_trigger;
            }

        // ----- data fields ---------------------------------------------------------------------

        /**
         * The number of nanoseconds previously accumulated before the timer was paused.
         */
        private long m_cNanosPrevSum;

        /**
         * The value (in nanos) when the timer was started, or 0 if the timer is paused.
         */
        private volatile long m_cNanosStart;

        /**
         * The registered alarms.
         */
        private ListSet<Alarm> f_setAlarms = new ListSet<>();
        }

    // ----- constants -----------------------------------------------------------------------------

    protected static final Timer    TIMER             = xLocalClock.TIMER;
    protected static final long     PICOS_PER_NANO    = 1_000;
    protected static final LongLong PICOS_PER_NANO_LL = new LongLong(PICOS_PER_NANO);
    protected static final long     NANOS_PER_MILLI   = 1_000_000;


    // ----- constants -----------------------------------------------------------------------------

    /**
     * Alarm counter.
     */
    private static int s_cAlarms;

    /**
     * Cached Duration class.
     */
    private static TypeComposition s_clzDuration;
    }
