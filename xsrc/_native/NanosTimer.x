/**
 * Simple timer (stop-watch) using Java's nanosecond-resolution "System" clock.
 */
class NanosTimer
        implements Timer
    {
    @Override
    void start();

    @Override
    @Atomic @RO Duration elapsed;

    @Override
    Cancellable schedule(Duration delay, Alarm alarm);

    @Override
    void stop();

    @Override
    void reset();

    @Override
    Duration resolution.get()
        {
        return Duration.NANOSEC;
        }
    }