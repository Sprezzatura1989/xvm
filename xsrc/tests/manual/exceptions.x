module TestTry.xqiz.it
    {
    @Inject X.io.Console console;

    void run()
        {
        console.println("Exception tests:");

        try
            {
            testBasic();
            }
        catch (Exception e)
            {
            console.println("UNEXPECTED THROW in testBasic(): " + e);
            }

        try
            {
            testUsing();
            }
        catch (Exception e)
            {
            console.println("UNEXPECTED THROW in testUsing(): " + e);
            }

        try
            {
            testFinally();
            console.println("TEST ERROR!!!");
            }
        catch (Exception e)
            {
            console.println("expected throw in testFinally(): " + e);
            }

        try
            {
            testAssert(-1);
            console.println("TEST ERROR!!!");
            }
        catch (Exception e)
            {
            console.println("expected throw in testAssert(): " + e);
            }

        try
            {
            testAssert(17);
            console.println("TEST ERROR!!!");
            }
        catch (Exception e)
            {
            console.println("expected throw in testAssert(): " + e);
            }

        try
            {
            testAssert(3);
            console.println("(expected non-throw in testAssert())");
            }
        catch (Exception e)
            {
            console.println("TEST ERROR!!!  UNexpected throw in testAssert(): " + e);
            }

        try
            {
            testAssert2();
            console.println("TEST ERROR!!!");
            }
        catch (Exception e)
            {
            console.println("expected throw in testAssert2(): " + e);
            }

        testAssertOnce(True);
        testAssertOnce(False);

        testAssertSample();

        console.println("\nException tests: finished!");
        }

    void testThrow()
        {
        console.println("in testThrow()");
        throw new IllegalState("test");
        }

    void testBasic()
        {
        console.println("\n** testBasic()");

        try
            {
            testThrow();
            console.println("DIDN'T THROW!");
            }
        catch (Exception e)
            {
            console.println("caught: " + e);
            }

        console.println("done testBasic()");
        }

    void testUsing()
        {
        console.println("\n** testUsing()");

        try
            {
            using (ByeBye bye = new ByeBye())
                {
                testThrow();
                console.println("DIDN'T THROW!");
                }
            }
        catch (Exception e)
            {
            console.println("ok");
            }

        console.println("done");
        }

    class ByeBye
            implements X.Closeable
        {
        construct()
            {
            console.println("hello!");
            }

        @Override
        void close()
            {
            console.println("bye-bye!");
            }
        }

    void testFinally()
        {
        console.println("\n** testFinally()");

        FOR: for (Int i : 1..2)
            {
            console.println("iteration " + i);
            TRY: try
                {
                if (FOR.last)
                    {
                    console.println("throwing exception inside try");
                    testThrow();
                    }
                else
                    {
                    console.println("not throwing exception inside try");
                    }
                }
            finally
                {
                console.println("exception in finally: " + TRY.exception);
                }
            }

        console.println("done testFinally() - which shouldn't happen!");
        }

    /**
     * ** testAssert(-1)
     * OutOfBounds: i >= 0 && i < size(), i=-1
     *
     * ** testAssert(17)
     * OutOfBounds: i >= 0 && i < size(), i=17, size()=6
     *
     * ** testAssert(3)
     * (no assertion)
     */
    void testAssert(Int i)
        {
        console.println($"\n** testAssert({i})");

        assert:bounds i >= 0 && i < size();
        }

    Int size()
        {
        return 6;
        }

    void testAssert2()
        {
        console.println("\n** testAssert2()");

        Int x = 4;
        assert ++x <= 4;
        }

    void testAssertOnce(Boolean firstTime)
        {
        console.println("\n** testAssertOnce()");

        Int x = 42;
        try
            {
            assert:once x < size();

            // the assertion passed, which it shouldn't do, unless this is the second time through
            console.println(firstTime ? "[1st] ERR: should have asserted" : "[2nd] OK: skipped");
            }
        catch (Exception e)
            {
            console.println(firstTime ? "[1st] OK: assert" : "[2nd] ERR: should have skipped");
            console.println(e.text);
            }
        }

    void testAssertSample()
        {
        console.println("\n** testAssertSample()");

        Int x   = 99;
        Int ok  = 0;
        Int err = 0;
        for (Int i : 1..1000)
            {
            try
                {
                assert:rnd(100) x < size();
                ++ok;
                }
            catch (Exception e)
                {
                ++err;
                }
            ++x;
            }

        console.println($"results: ok={ok}, errs={err} (should be ~10)");
        }
    }