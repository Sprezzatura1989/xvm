/**
 * A FutureVar represents a result that may be asynchronously provided, allowing the caller to
 * indicate a response to the result.
 *
 *   service Pi
 *       {
 *       String calc(Int digits)
 *           {
 *           String value;
 *           // some calculation code goes here
 *           // ...
 *           return value;
 *           }
 *       }
 *
 *   void test()
 *       {
 *       @Inject Console console;
 *       Pi pi = new Pi();
 *
 *       // blocking call to the Pi calculation service - wait for 100 digits
 *       console.print(pi.calc(100));
 *
 *       // potentially async call to the Pi calculation service
 *       @Future String fs = pi.calc(99999);
 *       // it is not guaranteed that the calculation will occur asynchronously, but it is both
 *       // possible and likely, allowing code to execute after the call is made, but before the
 *       // call is complete. what the future allows is for the "what to do when the calculation is
 *       // completed" to be expressed in a series of simple steps.
 *       &fs.handle(e -> e.toString())
 *          .passTo(s -> console.print(s));
 *       }
 *
 * The FutureVar does override the behavior of the Ref interface in a few specific ways:
 * * The {@link assigned} property on a FutureVar indicates whether the future has completed, either
 *   successfully or exceptionally.
 * * The {@link peek} method performs a non-blocking examination of the future:
 * * * {@code peek} returns negatively iff the future has not completed.
 * * * {@code peek} throws an exception iff the future completed exceptionally.
 * * * {@code peek} returns positively with the result iff the future has completed successfully.
 * * The {@link get} method performs a blocking examination of the future:
 * * * {@code get} blocks until the future completes.
 * * * {@code get} throws an exception iff the future completed exceptionally.
 * * * {@code get} returns the result iff the future has completed successfully.
 * * The {@link set} method can only be invoked by completing the future; the future's value cannot
 *   be modified once it is set.
 */
mixin FutureVar<RefType>
        into Var<RefType>
        implements Closeable
    {
    /**
     * Future completion status:
     * * Pending: The future has not yet completed.
     * * Result: The future completed because the operation returned successfully.
     * * Error: The future completed because the operation threw an exception (which may indicate
     *   that the operation timed out).
     */
    enum Completion {Pending, Result, Error}

    /**
     * Tracks whether and how the future has completed.
     */
    public/private Completion completion = Pending;

    /**
     * True if the value of the future can be set.
     */
    private Boolean assignable = false;

    /**
     * The exception, if the future completes exceptionally.
     */
    private Exception? failure = null;

    /**
     * The function type used to notify dependent futures.
     */
    typedef function void (Completion, RefType?, Exception?) NotifyDependent;

    /**
     * The future that is chained to this future, that this future sends its completion result to.
     */
    protected NotifyDependent? notify = null;


    // ----- Ref interface -------------------------------------------------------------------------

    /**
     * Determine if the future has completed, either successfully or exceptionally.
     */
    @Override
    Boolean assigned.get()
        {
        return completion != Pending;
        }

    /**
     * Perform a blocking wait-for-result of the future:
     * * A call to get() will block until the future has completed (subject to any time-outs being
     *   enforced by the runtime).
     * * If the future completes exceptionally, then get() throws an exception. Note that a time-out
     *   results in a TimedOut.
     * * If the future completes successfully, get() returns the result of the future.
     */
    @Override
    RefType get()
        {
        waitForCompletion();

        if (completion == Error)
            {
            throw failure.as(Exception);
            }

        return super();
        }

    @Override
    void set(RefType value)
        {
        assert !assigned;
        if (assignable)
            {
            super(value);
            }
        else
            {
            complete(value);
            }
        }


    // ----- Closeable interface -------------------------------------------------------------------

    @Override
    void close()
        {
        completeExceptionally(new Closed());
        }


    // ----- composing future behavior -------------------------------------------------------------

    /**
     * Create and return a new future that will execute the specified function when this future
     * completes successfully, or immediately if this future has already completed successfully.
     *
     * * If this future completes exceptionally, the new future will complete exceptionally with
     *   the same exception as this future.
     * * If this future completes successfully, the new future will execute the specified function.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully.
     * * If the new future completes successfully, then its value will be the same as this
     *   future's value.
     */
    FutureVar! thenDo(function void () run)
        {
        return chain(new ThenDoStep(run));
        }

    /**
     * Create and return a new future that will execute the specified function passing the value of
     * this future, when this future completes successfully, or immediately if this future has
     * already completed successfully.
     *
     * * If this future completes exceptionally, the new future will complete exceptionally with
     *   the same exception as this future.
     * * If this future completes successfully, the new future will execute the specified function,
     *   passing the value of this future.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully.
     * * If the new future completes successfully, then its value will be the same as this
     *   future's value.
     */
    FutureVar! passTo(function void (RefType) consume)
        {
        return chain(new PassToStep(consume));
        }

    /**
     * Create and return a new future that will transform the value of this future into a
     * potentially new value of a potentially different type. When this future completes
     * successfully (or immediately if this future has already completed successfully), the new
     * future will execute the specified value transformation function passing the value of this
     * future.
     *
     * * If this future completes exceptionally, the new future will complete exceptionally with
     *   the same exception as this future.
     * * If this future completes successfully, the new future will execute the specified function,
     *   passing the value of this future.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully with the value
     *   returned from the function.
     */
    <NewType> FutureVar!<NewType> transform(function NewType (RefType) convert)
        {
        return chain(new TransformStep<NewType, RefType>(convert));
        }

    /**
     * Create and return a new future that will handle an exceptional completion from this future.
     * When this future completes exceptionally (or immediately if this future has already completed
     * exceptionally), the new future will execute the specified exception-handling function,
     * allowing the new future to transform that exception into a value for a successful completion.
     *
     * * If this future completes successfully, the new future will complete successfully with the
     *   value of this future. (In other words, the new future is a pass-through for a successful
     *   completion.)
     * * If this future completes exceptionally, the new future will execute the specified function,
     *   passing the exception from this future.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully with the value
     *   returned from the function.
     */
    FutureVar! handle(function RefType (Exception) convert)
        {
        return chain(new HandleStep(convert));
        }

    /**
     * Create and return a new future that will have the opportunity to transform both successful
     * and exceptional completion of this future into either the successful or exceptional
     * completion of the new future. (In other words, this future can do pretty much anything it
     * wants with any completion input to create any completion output.) When this future completes
     * (or immediately if this future has already completed), the new future will execute the
     * specified function, passing the value (from this future's successful completion) and the
     * exception (from this future's exceptional completion).
     *
     * * If this future completes, the new future will execute the specified function, passing the
     *   value of this future and the exception of this future (at least one of which will be null).
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully with the value
     *   returned from the function.
     */
    <NewType> FutureVar!<NewType> transformOrHandle(function NewType (RefType?, Exception?) convert)
        {
        return chain(new Transform2Step<NewType, RefType>(convert));
        }

    /**
     * Create and return a new future that will complete when either *one* of _this_ future or the
     * _other_ specified future completes. In other words, in general terms, the first of two
     * futures to complete will trigger the completion of the new future. If one or both of the two
     * futures has already completed, then the new future will complete immediately. Note that the
     * ordering is _generally_ that the first of the two to complete will cause the completion of
     * the new future, but there are no strict ordering guarantees.
     *
     * * If this future or the other future completes successfully, and the new future has not
     *   already completed, then the new future will complete successfully with the same value.
     * * If this future or the other future completes exceptionally, and the new future has not
     *   already completed, then the new future will complete exceptionally with the same exception.
     */
    FutureVar!<RefType> or(FutureVar!<RefType> other)
        {
        return chain(new OrStep<RefType>(other));
        }

    /**
     * Create and return a new future that will complete when *any* *one* of _this_ future or the
     * _other_ specified futures completes. In other words, in general terms, the first of the
     * futures to complete will trigger the completion of the new future. If one or more of the
     * futures has already completed, then the new future will complete immediately. Note that the
     * ordering is _generally_ that the first of the futures to complete will cause the completion
     * of the new future, but there are no strict ordering guarantees.
     *
     * * If this future or one of the other futures completes successfully, and the new future has
     *   not already completed, then the new future will complete successfully with the same value.
     * * If this future or one of the other futures completes exceptionally, and the new future has
     *   not already completed, then the new future will complete exceptionally with the same
     *   exception.
     */
    FutureVar!<RefType> orAny(FutureVar!<RefType> ... others)
        {
        FutureVar<RefType> result = this;
        others.iterator().forEach(other -> {result = result.or(other);});
        return result;
        }

    /**
     * Create and return a new future that will complete when *both* of _this_ future and the
     * _other_ specified future completes successfully, or when *either* completes exceptionally.
     * If both of the two futures has already completed successfully, or one of the two futures has
     * already completed exceptionally, then the new future will complete immediately. Since the
     * types of the two futures can differ, a function must be provided to combine the two values
     * into a single result; a default function is provided to combine the two values into a tuple.
     *
     * * If this future or the other future completes exceptionally, and the new future has not
     *   already completed, then the new future will complete exceptionally with the same exception.
     * * If both this future and the other future completes successfully, and the new future has not
     *   already completed, then the provided function will be executed, passing the result of this
     *   future and the result of the other future.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully with the value
     *   returned from the function.
     */
    <OtherType, NewType> FutureVar!<NewType> and(FutureVar!<OtherType> other,
            function NewType (RefType, OtherType) combine)
        {
        return chain(new AndStep<NewType, RefType, OtherType>(other, combine));
        }

    /**
     * Create and return a new future that will execute the specified function on completion.
     *
     * * If this future completes, either successfully or exceptionally, then the new future will
     *   execute the specified function, passing the value of this future and the exception of this
     *   future (at least one of which will be null).
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully in the same manner
     *   (successfully or exceptionally) and with the same result (value or exception) as this
     *   future.
     */
    FutureVar!<RefType> whenComplete(function void (RefType?, Exception?) notify)
        {
        return chain(new WhenCompleteStep<RefType>(notify));
        }

    /**
     * Create and return a new future that will use the value of this future as the argument to a
     * function that will be executed in an attempted-asynchronous manner, with the result of that
     * function providing the completion of the new future. When this future completes
     * successfully (or immediately if this future has already completed successfully), the new
     * future will execute the specified asynchronous function, passing the value of this future.
     *
     * * If this future completes exceptionally, the new future will complete exceptionally with
     *   the same exception as this future.
     * * If this future completes successfully, the new future will execute the specified function
     *   in an attempted-asynchronous manner (i.e. requesting a future), passing the value of this
     *   future.
     * * If that function throws an exception, then the new future will complete exceptionally with
     *   that exception.
     * * If that function returns, then the new future will complete successfully with the value
     *   returned from the function.
     */
    <NewType> FutureVar!<NewType> createContinuation(function NewType (RefType) async)
        {
        return chain(new ContinuationStep<NewType, RefType>(async));
        }


    // ----- completion handling -------------------------------------------------------------------

    /**
     * Wait for the completion of the future.
     */
    FutureVar waitForCompletion()
        {
        while (completion == Pending)
            {
            this:service.yield();
            }

        return this;
        }

    /**
     * Cause the future to complete successfully with a result, if the future has not already
     * completed.
     */
    void complete(RefType result)
        {
        if (completion == Pending)
            {
            completion = Result;
            assignable = true;
            try
                {
                set(result);
                }
            finally
                {
                assignable = false;
                }
            thisCompleted(result, null);
            }
        }

    /**
     * Cause the future to complete exceptionally with an exception, if the future has not already
     * completed.
     */
    void completeExceptionally(Exception e)
        {
        if (completion == Pending)
            {
            completion = Error;
            failure    = e;
            thisCompleted(null, e);
            }
        }

    /**
     * For futures that complete with an exception, this allows a caller to obtain that exception.
     *
     * An exceptional completion causes both the {@link peek} and {@link get} methods to re-throw
     * the exception that the future completed with. This method provides a means to obtain that
     * exception without having to {@code catch} it.
     *
     * In much the same way that {@link peek} corresponds to {@link complete}, this {@code
     * peekException} method corresponds to {@link completeExceptionally}.
     */
    conditional Exception peekException()
        {
        if (completion == Error)
            {
            return true, failure.as(Exception);
            }

        return false;
        }

    /**
     * Internal method that has the once-and-only-once behavior associated with the future's
     * completion.
     */
    protected void thisCompleted(RefType? result, Exception? e)
        {
        // by default, the only completion logic is to chain the completion
        notify?(completion, result, e);
        notify = null;
        }

    /**
     * Add a DependentFuture to the list of things that this future must notify when it completes.
     * The DependentFuture contains a {@link DependentFuture.parentCompleted} method that is used as
     * a {@link NotifyDependent} function, allowing one or more FutureVar instances to notify it of
     * their completion. The FutureVar can chain to any number of DependentFuture instances.
     */
    <NewType> FutureVar!<NewType> chain(DependentFuture<NewType> nextFuture)
        {
        chain(nextFuture.parentCompleted);
        return nextFuture;
        }

    /**
     * Add a NotifyDependent function to the list of things that this future must notify when it
     * completes. The FutureVar can chain to any number of NotifyDependent functions.
     */
    void chain(NotifyDependent notify)
        {
        switch (completion)
            {
            case Pending:
                if (this.notify == null)
                    {
                    this.notify = notify;
                    }
                else
                    {
                    this.notify = new MultiCompleter<RefType>(this.notify.as(NotifyDependent), notify).parentCompleted;
                    }
                break;

            case Result:
                // this future has already completed, so notify the dependent
                notify(Result, get(), null);
                break;

            case Error:
                // this future has already completed, so notify the dependent
                notify(Error, null, failure);
                break;
            }
        }


    // ----- inner classes -------------------------------------------------------------------------

    /**
     * A DependentFuture is the base class for making simple futures that are dependent on the
     * result of another future. Specifically, a future invokes the {@link parentCompleted} method
     * of the DependentFuture, which in turn completes the future, which in turn invokes the next in
     * the chain.
     */
    static class DependentFuture<RefType, InputType>
            incorporates FutureVar<RefType>
            delegates Var<RefType>(resultVar)
        {
        void parentCompleted(Completion completion, InputType? input, Exception? e)
            {
            if (!assigned)
                {
                assert completion != Pending;
                if (completion == Result)
                    {
                    // this default implementation assumes that the InputType is the same as the
                    // RefType, i.e. the value is an as-is "pass through"; any sub-class that has a
                    // different RefType from the InputType must override this behavior
                    complete(input.as(RefType));
                    }
                else
                    {
                    completeExceptionally(e.as(Exception));
                    }
                }
            }

        private @Unassigned RefType result;
        private Var<RefType> resultVar.get()
            {
            return &result;
            }
        }

    /**
     * A MultiCompleter de-multiplexes a single dependent completion into multiple chained completions.
     * A MultiCompleter is not intended to be used as a normal future, but rather is used by other
     * figures solely to multiplex their own completion dependencies.
     */
    static class MultiCompleter<RefType>
            extends DependentFuture<RefType> // InputType == RefType
        {
        construct(NotifyDependent first, NotifyDependent second)
            {
            // while "notify" is a private property of the super-class, all properties with storage
            // (i.e. fields in the "this" struct) are visible at construction
            this.notify  = first;
            this.notify2 = second;
            }

        /**
         * The "multi" completer is actually a "bi" completer, and the FutureVar's implementation
         * of the chain method will link multiple of them together in a left-legged-only binary tree
         * in order to emulate a linked list of notifications (i.e. notifications are always
         * appended to the end of the list).
         */
        private NotifyDependent? notify2;

        @Override
        void chain(NotifyDependent chain)
            {
            // the MultiCompleter is used by other future instances to implement chaining, but
            // the MultiCompleter is not intended to be visible outside of those futures, and it
            // does not have its own dependents
            assert;
            }

        @Override
        protected void thisCompleted(RefType? result, Exception? e)
            {
            super(result, e);

            notify2?(completion, result, e);
            notify2 = null;
            }
        }

    /**
     * A dependent future that runs a function on the successful completion of its parent future,
     * and on successful completion of that function, this future completes with the same value as
     * its parent.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link run} function throws an exception, then this future completes exceptionally.
     */
    static class ThenDoStep<RefType>(function void () run)
            extends DependentFuture<RefType, RefType>
        {
        @Override
        void parentCompleted(Completion completion, RefType? input, Exception? e)
            {
            if (!assigned && completion == Result)
                {
                try
                    {
                    run();
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    return;
                    }
                }

            super(completion, input, e);
            }
        }

    /**
     * A dependent future that calls a function consuming the parent's result on the successful
     * completion of its parent future, and on successful completion of that function, this future
     * completes with the same value as its parent.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link consume} function throws an exception, then this future completes exceptionally.
     */
    static class PassToStep<RefType>(function void (RefType) consume)
            extends DependentFuture<RefType, RefType>
        {
        @Override
        void parentCompleted(Completion completion, RefType? input, Exception? e)
            {
            if (!assigned && completion == Result)
                {
                try
                    {
                    consume(input.as(RefType));
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    return;
                    }
                }

            super(completion, input, e);
            }
        }

    /**
     * Unlike most dependent futures, this dependent future handles the case in which its parent
     * completed _exceptionally_. If the parent completed exceptionally, then this future will call
     * a function to handle that exception, converting it to a usable value, and if the function
     * completes successfully, then this future will complete with that value.
     *
     * If the parent completed successfully, then this future completes successfully with the same
     * result.
     *
     * If the parent completed exceptionally and the {@link convert} function throws an exception,
     * then this future completes exceptionally.
     */
    static class HandleStep<RefType>(function RefType (Exception) convert)
            extends DependentFuture<RefType, RefType>
        {
        @Override
        void parentCompleted(Completion completion, RefType? input, Exception? e)
            {
            if (!assigned && completion == Error)
                {
                try
                    {
                    complete(convert(e.as(Exception)));
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    }
                }
            else
                {
                super(completion, input, e);
                }
            }
        }

    /**
     * A dependent future that runs a function on the completion of its parent future, providing the
     * function with the result if the parent completed successfully, and the exception if the
     * parent completed exceptionally.
     *
     * If the parent completed successfully, and the {@link notify} function does not throw an
     * exception, then this future completes successfully with the same result.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link notify} function throws an exception, then this future completes exceptionally.
     */
    static class WhenCompleteStep<RefType>(function void (RefType?, Exception?) notifyComplete)
            extends DependentFuture<RefType, RefType>
        {
        @Override
        void parentCompleted(Completion completion, RefType? input, Exception? e)
            {
            if (!assigned && completion != Pending)
                {
                try
                    {
                    notifyComplete(input, e);
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    return;
                    }
                }

            super(completion, input, e);
            }
        }

    /**
     * An OrStep is simply a junction point of two parents in which the first to signal completion
     * to this future will cause it to complete. In other words, only one _or_ the other needs to
     * complete in order for this future to complete.
     *
     * This future will complete iff one or more of its parents completes:
     * * If one of the parents completes successfully, then this future may complete successfully.
     * * If one of the parents completes exceptionally, then this future may complete exceptionally.
     * * Generally, it is expected that the first parent that notifies this future of the parent's
     *   completion will cause this future to complete.
     */
    static class OrStep<RefType>
            extends DependentFuture<RefType, RefType>
        {
        construct(FutureVar<RefType> other)
            {
            }
        finally
            {
            other.whenComplete((result, e) -> parentCompleted(e == null ? Result : Error, result, e));
            }
        }

    /**
     * An AndStep is simply a junction point of two parents in which the first to signal exceptional
     * completion or the second to signal successful completion will cause it to complete. In other
     * words, both of the parents must complete successfully in order for this future to complete
     * successfully.
     */
    static class AndStep<RefType, InputType, Input2Type>
            extends DependentFuture<RefType, InputType>
        {
        construct(FutureVar<Input2Type> other, function RefType (InputType, Input2Type) combine)
            {
            }
        finally
            {
            other.whenComplete((result, e) -> parent2Completed(e == null ? Result : Error, result, e));
            }

        public/private function RefType (InputType, Input2Type) combine;

        private @Unassigned InputType  input1;
        private @Unassigned Input2Type input2;

        /**
         * Handle the completion of the first parent.
         */
        @Override
        void parentCompleted(Completion completion, InputType? input, Exception? e)
            {
            if (!assigned)
                {
                assert completion != Pending;

                if (completion == Error)
                    {
                    completeExceptionally(e.as(Exception));
                    }
                else
                    {
                    assert completion == Result && input != null;

                    input1 = input;
                    if (&input2.assigned)
                        {
                        bothParentsCompleted();
                        }
                    }
                }
            }

        /**
         * Handle the completion of the second parent.
         */
        void parent2Completed(Completion completion, Input2Type? input, Exception? e)
            {
            if (!assigned)
                {
                assert completion != Pending;

                if (completion == Error)
                    {
                    completeExceptionally(e.as(Exception));
                    }
                else
                    {
                    assert completion == Result && input != null;

                    input2 = input;
                    if (&input1.assigned)
                        {
                        bothParentsCompleted();
                        }
                    }
                }
            }

        /**
         * Handle the successful completion of both parents.
         */
        private void bothParentsCompleted()
            {
            try
                {
                complete(combine(input1, input2));
                }
            catch (Exception e)
                {
                completeExceptionally(e);
                }
            }
        }

    /**
     * A dependent future that uses a provided {@link convert} function to convert the result of the
     * parent future from {@link InputType} to {@link RefType}, and then use the result value from
     * the conversion as the completion value for this future.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link convert} function throws an exception, then this future completes exceptionally.
     */
    static class TransformStep<RefType, InputType>(function RefType (InputType) convert)
            extends DependentFuture<RefType, InputType>
        {
        @Override
        void parentCompleted(Completion completion, InputType? input, Exception? e)
            {
            assert completion != Pending;

            if (!assigned && completion == Result)
                {
                try
                    {
                    complete(convert(input.as(InputType)));
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    }
                }
            else
                {
                super(completion, input, e);
                }
            }
        }

    /**
     * A dependent future that uses a provided {@link convert} function to convert the result of the
     * parent future from {@link InputType} to {@link RefType}, and then use the result value from
     * the conversion as the completion value for this future.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link convert} function throws an exception, then this future completes exceptionally.
     */
    static class Transform2Step<RefType, InputType>(function RefType (InputType?, Exception?) convert)
            extends DependentFuture<RefType, InputType>
        {
        @Override
        void parentCompleted(Completion completion, InputType? input, Exception? e)
            {
            if (!assigned)
                {
                assert completion != Pending;
                try
                    {
                    complete(convert(input, e));
                    }
                catch (Exception e2)
                    {
                    completeExceptionally(e2);
                    }
                }
            }
        }

    /**
     * A dependent future that uses a provided {@link async} function, which is then executed to
     * obtain a future result, which upon completion will complete this future with its result.
     *
     * If the parent completed exceptionally, or if the parent completed successfully but the
     * {@link async} function throws an exception, then this future completes exceptionally.
     */
    static class ContinuationStep<RefType, InputType>(function RefType (InputType) invokeAsync)
            extends DependentFuture<RefType, InputType>
        {
        protected FutureVar? asyncResult;

        @Override
        void parentCompleted(Completion completion, InputType? input, Exception? e)
            {
            if (!assigned)
                {
                assert completion != Pending;
                if (completion == Error)
                    {
                    super(completion, null, e);
                    }
                else if (asyncResult == null)
                    {
                    // this is the point at which the continuation is created, i.e. the input to the
                    // async call is now available, so the async call needs to be made, with the
                    // future result of the async call triggering the completion of _this_ future,
                    // thus forming a "continuation". note that the function may execute
                    // synchronously, at the whim of the runtime, but even if it does, the result
                    // (including exceptional result) will be captured in the future
                    @Future RefType async = invokeAsync(input.as(InputType));
                    asyncResult = &async;
                    asyncResult?.chain(asyncCompleted);
                    }
                }
            }

        /**
         * When the invoked function completes, its future invokes this method, allowing this future
         * to complete.
         */
        protected void asyncCompleted(Completion completion, RefType? result, Exception? e)
            {
            assert completion != Pending;
            if (completion == Result)
                {
                complete(result.as(RefType));
                }
            else
                {
                completeExceptionally(e.as(Exception));
                }
            }
        }
    }
