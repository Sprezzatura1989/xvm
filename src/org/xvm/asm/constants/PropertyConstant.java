package org.xvm.asm.constants;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.Set;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Component;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.GenericTypeResolver;
import org.xvm.asm.PropertyStructure;


/**
 * Represent a property constant, which identifies a particular property structure.
 */
public class PropertyConstant
        extends FormalConstant
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a constant whose value is a property identifier.
     *
     * @param pool         the ConstantPool that will contain this Constant
     * @param constParent  the module, package, class, or method that contains this property
     * @param sName        the property name
     */
    public PropertyConstant(ConstantPool pool, IdentityConstant constParent, String sName)
        {
        super(pool, constParent, sName);

        switch (constParent.getFormat())
            {
            case Module:
            case Package:
            case Class:
            case NativeClass:
            case Property:
            case Method:
                break;

            default:
                throw new IllegalArgumentException("invalid parent: " + constParent.getFormat());
            }
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
    public PropertyConstant(ConstantPool pool, Format format, DataInput in)
            throws IOException
        {
        super(pool, format, in);
        }

    @Override
    protected void resolveConstants()
        {
        super.resolveConstants();
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
        assert isFormalType();

        // the type of the property must be "Type<X>", so return X
        TypeConstant typeConstraint = getType();
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
        if (isTypeSequenceTypeParameter())
            {
            // the following block is for nothing else, but compilation of Tuple and
            // ConditionalTuple natural classes
            if (resolver instanceof TypeConstant)
                {
                TypeConstant typeResolver = (TypeConstant) resolver;
                if (typeResolver.isTuple() && !typeResolver.isParamsSpecified())
                    {
                    return null;
                    }
                }

            TypeConstant typeResolved = resolver.resolveGenericType(getName());

            // prevent a recursive resolution into "Tuple<...>"
            return typeResolved == null || typeResolved.isFormalTypeSequence()
                    ? null
                    : typeResolved;
            }

        return resolver.resolveGenericType(getName());
        }

    // ----- type-specific functionality -----------------------------------------------------------

    /**
     * @return a signature constant representing this property
     */
    public SignatureConstant getSignature()
        {
        SignatureConstant sig = m_constSig;
        if (sig == null)
            {
            // transient synthetic constant; no need to register
            sig = m_constSig = new SignatureConstant(getConstantPool(), this);
            }
        return sig;
        }

    /**
     * @return a TypeConstant representing a formal type represented by this property,
     *         which must be a type parameter
     */
    public TypeConstant getFormalType()
        {
        assert isFormalType();
        return getConstantPool().ensureTerminalTypeConstant(this);
        }

    /**
     * @return true iff this property is a type parameter
     */
    public boolean isFormalType()
        {
        PropertyStructure struct = (PropertyStructure) getComponent();
        return struct != null && struct.isGenericTypeParameter();
        }

    /**
     * @return true iff this property is a formal type parameter that materializes into a
     *         sequence of types
     */
    public boolean isTypeSequenceTypeParameter()
        {
        return isFormalType() && getConstraintType() instanceof TypeSequenceTypeConstant;
        }


    // ----- Constant methods ----------------------------------------------------------------------

    @Override
    public Format getFormat()
        {
        return Format.Property;
        }

    @Override
    public boolean isProperty()
        {
        return true;
        }

    @Override
    public TypeConstant getRefType(TypeConstant typeTarget)
        {
        if (typeTarget == null)
            {
            typeTarget = getClassIdentity().getType();
            }

        PropertyInfo infoThis = typeTarget.ensureTypeInfo().findProperty(this);

        assert infoThis != null;

        return infoThis.isCustomLogic()
                ? getConstantPool().ensurePropertyClassTypeConstant(typeTarget, this)
                : infoThis.getBaseRefType();
        }

    @Override
    public TypeConstant getType()
        {
        TypeConstant type = m_type;
        if (type == null)
            {
            m_type = type = ((PropertyStructure) getComponent()).getType();
            }
        return type;
        }

    @Override
    public Object getNestedIdentity()
        {
        // property can be identified with only a name, assuming it is not recursively nested
        return getNamespace().isNested()
                ? new NestedIdentity()
                : getName();
        }

    @Override
    public Object resolveNestedIdentity(ConstantPool pool, GenericTypeResolver resolver)
        {
        // property can be identified with only a name, assuming it is not recursively nested
        return getNamespace().isNested()
                ? new NestedIdentity(resolver)
                : getName();
        }

    @Override
    public PropertyStructure relocateNestedIdentity(ClassStructure clz)
        {
        Component parent = getNamespace().relocateNestedIdentity(clz);
        if (parent == null)
            {
            return null;
            }

        Component that = parent.getChild(this.getName());
        return that instanceof PropertyStructure
                ? (PropertyStructure) that
                : null;
        }

    @Override
    public PropertyConstant ensureNestedIdentity(ConstantPool pool, IdentityConstant that)
        {
        return pool.ensurePropertyConstant(
                getParentConstant().ensureNestedIdentity(pool, that), getName());
        }

    @Override
    public IdentityConstant appendTrailingSegmentTo(IdentityConstant that)
        {
        return that.getConstantPool().ensurePropertyConstant(that, getName());
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void registerConstants(ConstantPool pool)
        {
        m_type     = null;
        m_constSig = null;

        super.registerConstants(pool);
        }

    @Override
    protected void assemble(DataOutput out) throws IOException
        {
        super.assemble(out);

        m_type     = null;
        m_constSig = null;
        }

    @Override
    public String getDescription()
        {
        StringBuilder sb = new StringBuilder();
        sb.append(getName());
        IdentityConstant idParent = getNamespace();
        while (idParent != null)
            {
            switch (idParent.getFormat())
                {
                case Method:
                case Property:
                    sb.insert(0, idParent.getName() + '#');
                    idParent = idParent.getNamespace();
                    break;

                default:
                    idParent = null;
                }
            }

        return "property=" + sb.toString();
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * Cached type.
     */
    private transient TypeConstant m_type;

    /**
     * Cached constant that represents the signature of this property.
     */
    private transient SignatureConstant m_constSig;
    }
