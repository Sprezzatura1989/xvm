/**
 * A Ref represents a <i>reference</i> to an Ecstasy object. In Ecstasy, "everything
 * is an object", and the only way that one can interact with an object is through
 * a reference to that object. The <i>referent</i> is the object being referred to;
 * the <i>reference</i> (encapsulated in and represented by a Ref object) is the
 * object that refers to the referent.
 * <p>
 * An Ecstasy reference is conceptually composed of two pieces of information:
 * <ol>
 * <li>A type;</li>
 * <li>An identity.</li>
 * </ol>
 * <p>
 * The type portion of an Ecstasy reference (represented by the ActualType property
 * of the Ref) is simply the set of operations that can be invoked against the
 * referent. Regardless of the actual operations that the referent implements, only
 * those present in the type of the reference can be invoked through the reference.
 * This allows references to be purposefully narrowed; an obvious example is when an
 * object only provides a reference to its <tt>public</tt> members.
 * <p>
 * (The Ref also has a RefType property, which is its type constraint. For example,
 * when a Ref represents a compile time concept such as a variable or a property,
 * the RefType is the <i>compile time type</i> of the reference. The reference may
 * contain additional operations at runtime; the ActualType is always a super-set
 * (⊇) of the RefType.)
 * <p>
 * The identity portion of an Ecstasy reference is itself unrepresentable in Ecstasy.
 * In fact, it is this very unrepresentability that necessitates the Ref abstraction
 * in the first place. For example, the identity may be implemented as a pointer,
 * which points to an address in memory at which the state of the object is stored.
 * However, that address could be located on the process' program stack, or allocated
 * via a dynamic memory allocation, or could point into a particular element of an
 * array or a structure that itself is located on the program stack or allocated via
 * a dynamic memory allocation. Or the identity could be a handle, adding a layer of
 * indirection to each of the above. Or the identity could itself <i>be</i> the
 * object, as one would expect for the simplest (the most primitive) of types, such
 * as booleans, bytes, characters, and integers.
 * <p>
 * To allow the Ecstasy runtime to provide the same behavioral guarantees regardless
 * of how objects are allocated and managed, how they are addressed, and how house-
 * keeping activities potentially affect all of the above, the Ref provides an opaque
 * abstraction that hides the actual identity (and thus the actual underlying
 * implementation of the type) from the program and from the programmer.
 * <p>
 * Because it is impossible to represent the identity in Ecstasy, the Ref type is
 * itself simply an interface; the actual Ref instances used for parameters,
 * variables, properties, array elements, and so on, are provided by the runtime
 * itself, and exposed to the running code via this interface.
 *
 * @Copyright 2016 xqiz.it
 */
interface Ref<RefType>
        extends Referent
    {
    /**
     * De-reference the reference to obtain the referent.
     */
    RefType get();

    /**
     * Specify the referent for this reference.
     */
    Void set(RefType value);

    /**
     *
     */
    @ro Boolean strong;

    /**
     * Determine if there is a referent. There are a few specific cases in which a reference may
     * not have a referent, including:
     * <ul>
     * <li>Conditional return values;</li>
     * <li>Uninitialized fields in an object structure during construction;</li>
     * <li>Lazy references that have not yet lazily populated;</li>
     * <li>Soft or weak references that have had their referents collected.</li>
     * </ul>
     */
    @ro Boolean assigned;

    /**
     * TODO only works if this is mutable and there is a default value, or if called by runtime
     */
    Void clear();

    /**
     * TODO explain why this is important for @lazy, @future, @soft, @weak
     */
    conditional RefType peek()
        {
        // the following is treated as a critical section by the runtime
        if (assigned)
            {
            return true, get();
            }

        return false;
        }

    /**
     * Obtain the actual runtime type of the reference that this Ref currently
     * holds. The ActualType represents the full set of methods that can be
     * invoked against the referent, and is always a super-set of the RefType:
     * <p>
     * <tt>ActualType ⊇ RefType</tt>
     * <p>
     * (The RefType denotes the constraint of the reference, i.e. the reference
     * must "be of" the RefType, but is not limited to only having the methods of
     * the RefType; the RefType is often the <i>compile-time type</i> of the
     * reference.)
     */
    @ro Type ActualType;

    /**
     * Transform the reference such that it contains the methods in the specified
     * type.
     * <p>
     * For any reference, this method will narrow the reference so that it contains
     * only the methods in the specified type. This strips the runtime reference of
     * any methods that are not present in the specified type.
     * <p>
     * For a reference to an object from the same module as the caller, this method
     * allows the reference to be widened as well.
     */
    @ro AsType as(Type AsType);

    /**
     * Reference equality is used to determine if two references are referring to
     * the same referent <i>identity</i>. Specifically, two references are equal
     * iff they reference the same runtime object, or the two objects that they
     * reference are both immutable and structurally identical.
     * <p>
     * Because the reference identity is impossible to represent in Ecstasy, the
     * actual implementation of this function is also impossible to represent in
     * Ecstasy, hence the implementation may appear to be infinitely recursive.
     */
    static Boolean equals(Ref value1, Ref value2)
        {
        return value1 == value2;
        }

    /**
     * The optional name of the reference. References are used for arguments, local
     * variables, object properties, constant pool values, array elements, fields of
     * structures, elements of tuples, and many other purposes; in some of these uses,
     * it is common for a reference to be named. For example, arguments, local variables,
     * struct fields, and properties are almost always named, but tuple elements are often
     * not named, and array elements are never named.
     */
    @ro String? name;

    /**
     * The reference uses a number of bytes for its own storage; while the size of the
     * reference is not expected to dynamically change, reference sizes may vary from one
     * reference to another. References may be larger than expected, because references
     * may include additional information (and potentially even the entire referent!)
     * within the reference itself.
     */
    @ro Int byteLength;

    /**
     * Determine if the reference is completely self-contained, in that the referent is
     * actually embedded within the reference itself.
     */
    @ro Boolean selfContained;
    }
