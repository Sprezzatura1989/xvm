module TestReflection.xqiz.it
    {
    @Inject Ecstasy.io.Console console;

    void run()
        {
        testInstanceOf();
        testMaskReveal();
        }

    const Point(Int x, Int y);

    void testInstanceOf()
        {
        import Ecstasy.collections.HashMap;

        console.println("\n** testInstanceOf");

        Object o = new HashMap<Int, String>();
        assert &o.instanceOf(Map<Int, String>);
        assert !&o.instanceOf(Map<String, String>);

        Point p = new Point(1, 1);
        assert &p.implements_(Stringable);

        const Point3(Int x, Int y, Int z) extends Point(x, y);

        Point3 p3 = new Point3(1, 1, 1);
        assert &p3.extends_(Point);

        Interval<Int> range = 0..5;
        assert &range.incorporates_(Range);
        }

    void testMaskReveal()
        {
        import Ecstasy.fs.Directory;

        console.println("\n** testMaskReveal");

        @Inject Directory tmpDir;

        // the implementation of tmpDir is a "const OSDirectory", which is definitely Stringable,
        // but they must not be able to use that fact when in a different container;
        // since the tests for now run "in-container", the revealAs() would work

        console.println($"tmpDir=" + tmpDir.toString());

        assert !tmpDir.is(Stringable);
        assert !&tmpDir.instanceOf(Stringable);
        assert !&tmpDir.implements_(Stringable);

        try
            {
            Stringable str = tmpDir.as(Stringable);
            assert;
            }
        catch (Exception e)
            {
            console.println($"expected - {e.text}");
            }

        assert Stringable str := &tmpDir.revealAs<Stringable>();
        console.println($"str.length={str.estimateStringLength()}");

        Point p = new Point(1, 1);
        str = &p.maskAs<Stringable>();

        assert !&str.instanceOf(Point);
        try
            {
            p = str.as(Point);
            assert;
            }
        catch (Exception e)
            {
            console.println($"expected - {e.text}");
            }

        assert p := &str.revealAs<Point>();
        console.println($"p={p}");
        }
    }