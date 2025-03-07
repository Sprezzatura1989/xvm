package org.xvm.asm.constants;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.Set;

import java.util.function.Consumer;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.GenericTypeResolver;
import org.xvm.asm.MethodStructure;

import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.writePackedLong;


/**
 * Represent a type parameter constant, which specifies a particular virtual machine register.
 */
public class TypeParameterConstant
        extends FormalConstant
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a constant whose value is a type parameter identifier.
     *
     * @param pool   the ConstantPool that will contain this Constant
     * @param sName  the type parameter name
     * @param iReg   the register number
     */
    public TypeParameterConstant(ConstantPool pool, MethodConstant constMethod, String sName, int iReg)
        {
        super(pool, constMethod, sName);

        if (iReg < 0 || iReg > 0xFF)    // arbitrary limit; basically just a sanity assertion
            {
            throw new IllegalArgumentException("register (" + iReg + ") out of range");
            }

        m_iReg = iReg;
        }

    /**
     * Constructor used for deserialization.
     *
     * @param pool    the ConstantPool that will contain this Constant
     * @param format  the format of the Constant in the stream
     * @param in      the DataInput stream to read the Constant value from
     *
     * @throws IOException  if an issue occurs reading the Constant value
     */
    public TypeParameterConstant(ConstantPool pool, Format format, DataInput in)
            throws IOException
        {
        super(pool, format, in);

        m_iReg = readMagnitude(in);
        }

    @Override
    protected void resolveConstants()
        {
        super.resolveConstants();
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return the MethodConstant that the register belongs to
     */
    public MethodConstant getMethod()
        {
        return (MethodConstant) getParentConstant();
        }

    /**
     * Bind this {@link TypeParameterConstant} to the specified MethodConstant
     */
    public void bindMethod(MethodConstant method)
        {
        replaceParent(method);
        }

    /**
     * @return the register number (zero based)
     */
    public int getRegister()
        {
        return m_iReg;
        }


    // ----- FormalConstant methods ----------------------------------------------------------------

    @Override
    public TypeConstant getConstraintType()
        {
        // the type points to a register, which means that the type is a parameterized type;
        // the type of the register will be "Type<X>", so return X
        MethodConstant   constMethod = getMethod();
        int              nReg        = getRegister();
        TypeConstant[]   atypeParams = constMethod.getRawParams();
        assert atypeParams.length > nReg;
        TypeConstant typeConstraint  = atypeParams[nReg];
        assert typeConstraint.isEcstasy("Type") && typeConstraint.isParamsSpecified();

        typeConstraint = typeConstraint.getParamTypesArray()[0];
        if (!typeConstraint.isParamsSpecified() && typeConstraint.isSingleUnderlyingClass(true))
            {
            // create a normalized formal type
            ConstantPool   pool = getConstantPool();
            ClassStructure clz  = (ClassStructure) typeConstraint.getSingleUnderlyingClass(true).getComponent();
            if (clz.isParameterized())
                {
                Set<StringConstant> setFormalNames = clz.getTypeParams().keySet();
                TypeConstant[]      atypeFormal    = new TypeConstant[setFormalNames.size()];
                int ix = 0;
                for (StringConstant constName : setFormalNames)
                    {
                    Constant constant = pool.ensureFormalTypeChildConstant(this, constName.getValue());
                    atypeFormal[ix++] = constant.getType();
                    }
                typeConstraint = pool.ensureParameterizedTypeConstant(typeConstraint, atypeFormal);
                }
            }
        return typeConstraint;
        }

    @Override
    public TypeConstant resolve(GenericTypeResolver resolver)
        {
        MethodStructure method = (MethodStructure) getMethod().getComponent();
        return method == null
                ? null
                : resolver.resolveGenericType(getName());
        }


    // ----- IdentityConstant methods --------------------------------------------------------------

    @Override
    public IdentityConstant appendTrailingSegmentTo(IdentityConstant that)
        {
        return that.getConstantPool().ensureRegisterConstant(
                (MethodConstant) that, getRegister(), getName());
        }


    // ----- Constant methods ----------------------------------------------------------------------


    @Override
    public TypeConstant getType()
        {
        return getConstantPool().ensureTerminalTypeConstant(this);
        }

    @Override
    public Format getFormat()
        {
        return Format.TypeParameter;
        }

    @Override
    public boolean containsUnresolved()
        {
        if (fResolved || fReEntry)
            {
            return false;
            }

        fReEntry = true;
        try
            {
            return !(fResolved = !getMethod().containsUnresolved());
            }
        finally
            {
            fReEntry = false;
            }
        }

    @Override
    public boolean canResolve()
        {
        // as soon as the containing MethodConstant knows where it exists in the universe, then we
        // can safely resolve names
        return getParentConstant().getParentConstant().canResolve();
        }

    @Override
    public void forEachUnderlying(Consumer<Constant> visitor)
        {
        // the method constant is not "a child"; this would cause an infinite loop
        }

    @Override
    public TypeParameterConstant resolveTypedefs()
        {
        // There is a circular dependency that involves TypeParameterConstant:
        //
        // MethodConstant -> SignatureConstant -> TerminalTypeConstant -> TypeParameterConstant -> MethodConstant
        //
        // To break the circle, TypeParameterConstant is not being responsible for resolving the
        // corresponding MethodConstant; instead it's the MethodConstant's duty to call bindMethod()
        // on TypeParameterConstant (via TypeConstant#bindTypeParameters() API) as soon as the
        // MethodConstant is resolved (see MethodConstant#resolveTypedefs()).
        return this;
        }

    @Override
    protected Object getLocator()
        {
        return m_iReg == 0
                ? getMethod()
                : null;
        }

    @Override
    protected int compareDetails(Constant that)
        {
        if (!(that instanceof TypeParameterConstant))
            {
            return -1;
            }

        TypeParameterConstant regThat = (TypeParameterConstant) that;
        int nDif = this.m_iReg - regThat.m_iReg;
        if (nDif != 0 || fReEntry)
            {
            return nDif;
            }

        fReEntry = true;
        try
            {
            return getParentConstant().compareTo(regThat.getParentConstant());
            }
        finally
            {
            fReEntry = false;
            }
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        super.assemble(out);

        writePackedLong(out, m_iReg);
        }

    @Override
    public String getDescription()
        {
        return "method=" + getMethod().getName() + ", register=" + m_iReg;
        }


    // ----- Object methods ------------------------------------------------------------------------

    @Override
    public int hashCode()
        {
        if (fReEntry)
            {
            return m_iReg;
            }

        fReEntry = true;
        try
            {
            return getName().hashCode() + m_iReg;
            }
        finally
            {
            fReEntry = false;
            }
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * The register index.
     */
    private int m_iReg;

    private transient boolean fReEntry;
    private transient boolean fResolved;
    }
