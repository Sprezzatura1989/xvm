
interface List<ElementType>
    {
    ElementType getElement(Int index);

    List<ElementType> subListStartingWith(Int index);

    Void addAll(List<ElementType> elements);
    }

class SuperCoolList<ElementType>
    {
    ElementType getElement(Int index);

    List<ElementType> subListStartingWith(Int index)
        {
        // I can't return a SuperCoolList to do the sub-list for some reason
        // ...
        return SomeOtherHandyHelperDelegatingList(this, index);
        }

    Void addAll(List<ElementType> elements)
        {
        // ...
        }
    }

// conditional mix-ins

class SuperList<ElementType>
        extends SimpleList<ElementType>
        incorporates conditional NumberCollectionHelper<ElementType extends Number>
    {
    // ...
    }

// compositions with mix-ins etc.

interface Stream
    {
    Byte readByte();
    }

mixin ObjectStreaming into Stream
    {
    Byte readByte()
        {
        return super.readByte();
        }

    Object readObject();
    Void writeObject(Object o);
    }

@ObjectStreaming class FileObjectStream
        extends FileStream
    {
    // ...
    }

class FileObjectStream
        extends FileStream
        incorporates ObjectStreaming
    {
    // ...
    }

ObjectStreaming stream = new @ObjectStreaming FileStream();

class FileObjectStream
        extends Object
        implements Stream
        incorporates StreamFunctionality
        incorporates ObjectStreaming
    {
    // ...
    }


// can't super to a method that isn't guaranteed to already be in the composition in the layer BEFORE the incorporates
// (compiler/verifier would have to figure this out.)

interface Runnable
    {
    Void run();
    }

mixin RunBySuperCall
        into Runnable
    {
    Void run()
        {
        super();
        }
    }

class InvalidExample
        implements Runnable
        incorporates RunBySuperCall
    {
    Void run()
        {
        // ...
        }
    }

@RunBySuperCall
class ValidExample
        implements Runnable
    {
    Void run()
        {
        // ...
        }
    }


// but ***can*** assume that the method is present on "this" (because the mixin knows that the
// type of "this" is Object+Runnable)

interface Runnable
    {
    Void run();
    }

mixin RunBySuperCall
        into Runnable
    {
    Void run2()
        {
        run();
        }
    }

class NoLongerInvalidExample
        implements Runnable
        incorporates RunBySuperCall
    {
    Void run()
        {
        // ...
        }
    }

@RunBySuperCall
class ValidExample
        implements Runnable
    {
    Void run()
        {
        // ...
        }
    }


// call chains

class Object
    {
    // ...
    }

class Base
        extends Object
        incorporates BaseMixin
        implements InterfaceOne
    {
    Void foo()
        {
        print("I'm on Base");

        // can I super?
        super();
        }
    }

class Derived
        extends Base
        incorporates DerivedMixin
        implements InterfaceTwo
    {
    Void foo()
        {
        print("I'm on Derived");
        super();
        }
    }

interface InterfaceOne
    {
    Void foo()
        {
        print("I'm in interface one");
        // can NOT super
        }
    }

interface InterfaceTwo
    {
    Void foo()
        {
        print("I'm in interface two");
        // can NOT super
        }
    }

mixin BaseMixin
        into InterfaceOne
    {
    Void foo()
        {
        // can I super?
        super();
        }
    }

mixin DerivedMixin
        into InterfaceTwo
    {
    Void foo()
        {
        // can I super?
        super();
        }
    }

// ----- call chain

// There are two parts on the call chain:
//  1. The "declared" chain that consists of
//   1.1 declared methods on the encapsulating mixins and traits (recursively)
//   1.2 methods implemented by the class
//   1.3 declared methods on the incorporated mixins, traits and delegates
//   ... followed by the "declared" chain on the super class all the way down to Object (including)
//
// 2. The "default" chain that consists of  
//   2.1 default methods on the interfaces that are declared by encapsulating
//          mixins and traits (recursively)
//   2.2 default methods on the interfaces declared by the class (recursively)
//   2.3 default methods on the interfaces that are declared by all other contributions (recursively)
//   ... followed by the "default" chain on the super class all the way down to Object (excluding)


// ---- subtraction of interface

interface SqlDriver
    {
    Void a();
    Int b();
    String c();
    }

class Base
    {
    Void a()
        {
        print("I'm on Base");
        }
    }

class Derived
        extends Base
        delegates (SqlDriver - Base)(mockDriver)
    {
    @Lazy SqlDriver mockDriver.calc()
        {
        return new MockingSqlDriver();
        }
    }


// parameterized types

interface Map<KeyType, ElementType>
    {
    KeyType first();        // return value type constant is Terminal(Property(ThisClass, "KeyType"))
    }


// X atomic impl in Java
boolean replace(AtomicRef ref, GeneClass clz, ObjectHandle expected, ObjectHandle newval)
    {
    if (ref.replace(expected, newval))
        {
        return true;
        }

    ObjectHandle oldval;
    while (Utils.equals(clz, oldval = ref.get(), expected))
        {
        if (ref.replace(oldval, newval))
            {
            return true;
            }
        }
    }


// ----- type resolution challenges

interface Map<KeyType, ValueType> {...}

class MyClass<MapType1 extends Map, MapType2 extends Map>
    {
    Void process(MapType1.KeyType k1, MapType2.KeyType k2)  // TODO resolve both "KeyType" correctly
        {
        // ...
        }

    <MT3 extends MapType1, KT3 extends MapType1.KeyType> Void process(MT3.KeyType k, KT3 k3)  // TODO resolve both "KeyType" correctly
        {
        // ...
        }
    }

