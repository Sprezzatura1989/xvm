package org.xvm.asm.constants;


import java.io.DataInput;
import java.io.IOException;

import org.xvm.asm.ConstantPool;
import org.xvm.asm.GenericTypeResolver;


/**
 * Represent a formal child of a generic property, type parameter or formal child constant.
 */
public class FormalTypeChildConstant
        extends FormalConstant
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Constructor used for deserialization.
     *
     * @param pool    the ConstantPool that will contain this Constant
     * @param format  the format of the Constant in the stream
     * @param in      the DataInput stream to read the Constant value from
     *
     * @throws IOException  if an issue occurs reading the Constant value
     */
    public FormalTypeChildConstant(ConstantPool pool, Format format, DataInput in)
            throws IOException
        {
        super(pool, format, in);
        }

    /**
     * Construct a constant that represents the class of a non-static child whose identity is
     * auto-narrowing.
     *
     * @param pool         the ConstantPool that will contain this Constant
     * @param constParent  the parent constant, which must be a FormalConstant
     * @param sName        the formal child name
     */
    public FormalTypeChildConstant(ConstantPool pool, IdentityConstant constParent, String sName)
        {
        super(pool, constParent, sName);

        switch (constParent.getFormat())
            {
            case FormalTypeChild:
            case TypeParameter:
                break;

            case Property:
                if (((PropertyConstant) constParent).isFormalType())
                    {
                    break;
                    }
                // fall through
            default:
                throw new IllegalArgumentException(
                    "parent does not represent a formal constant: " + constParent);
            }
        }


    // ----- type-specific functionality -----------------------------------------------------------

    /**
     * @return the top formal parent of this formal child
     */
    public IdentityConstant getTopParent()
        {
        IdentityConstant idParent = getParentConstant();
        while (idParent.getFormat() == Format.FormalTypeChild)
            {
            idParent = idParent.getParentConstant();
            }
        return idParent;
        }


    // ----- FormalConstant methods ----------------------------------------------------------------

    /**
     * Dereference a property constant that is used for a type parameter, to obtain the constraint
     * type of that type parameter.
     *
     * @return the constraint type of the type parameter
     */
    @Override
    public TypeConstant getConstraintType()
        {
        FormalConstant idParent       = (FormalConstant) getParentConstant();
        TypeConstant   typeConstraint = idParent.getConstraintType();

        assert typeConstraint.containsGenericParam(getName());

        TypeConstant type = typeConstraint.getSingleUnderlyingClass(true).getFormalType().
                                resolveGenericType(getName());
        assert type.isGenericType();

        PropertyConstant idProp = (PropertyConstant) type.getDefiningConstant();
        return idProp.getConstraintType();
        }

    @Override
    public TypeConstant resolve(GenericTypeResolver resolver)
        {
        FormalConstant idParent     = (FormalConstant) getParentConstant();
        TypeConstant   typeResolved = idParent.resolve(resolver);

        return typeResolved == null
                ? null
                : typeResolved.resolveGenericType(getName());
        }


    // ----- IdentityConstant methods --------------------------------------------------------------

    @Override
    public IdentityConstant appendTrailingSegmentTo(IdentityConstant that)
        {
        return that.getConstantPool().ensureFormalTypeChildConstant(that, getName());
        }


    // ----- Constant methods ----------------------------------------------------------------------

    @Override
    public Format getFormat()
        {
        return Format.FormalTypeChild;
        }

    @Override
    public boolean isClass()
        {
        return false;
        }

    @Override
    public boolean containsUnresolved()
        {
        return false;
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    public String getDescription()
        {
        return "parent=" + getParentConstant() + ", child=" + getName();
        }
    }
