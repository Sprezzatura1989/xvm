package org.xvm.asm.constants;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import java.util.function.Consumer;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.GenericTypeResolver;

import org.xvm.util.Severity;

import static org.xvm.util.Handy.readIndex;
import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.writePackedLong;


/**
 * A TypeConstant that represents a parameterized type.
 */
public class ParameterizedTypeConstant
        extends TypeConstant
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a constant whose value is a type-parameterized type.
     *
     * @param pool             the ConstantPool that will contain this Constant
     * @param constType        a TypeConstant representing the parameterized type
     * @param constTypeParams  a number of TypeConstants representing the type parameters
     */
    public ParameterizedTypeConstant(ConstantPool pool, TypeConstant constType,
                                     TypeConstant... constTypeParams)
        {
        super(pool);

        if (constType == null)
            {
            throw new IllegalArgumentException("type required");
            }
        if (constType.isParamsSpecified())
            {
            throw new IllegalArgumentException("type is already parameterized");
            }

        switch (constType.getFormat())
            {
            case TerminalType:
            case VirtualChildType:
            case AnonymousClassType:
            case UnresolvedType:
                break;

            default:
                throw new IllegalArgumentException("Invalid format: " + constType);
            }

        if (constTypeParams == null)
            {
            throw new IllegalArgumentException("must have parameters");
            }

        m_constType   = constType;
        m_atypeParams = constTypeParams;
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
    public ParameterizedTypeConstant(ConstantPool pool, Format format, DataInput in)
            throws IOException
        {
        super(pool);

        m_iType = readIndex(in);

        int cTypes = readMagnitude(in);
        if (cTypes > 0)
            {
            int[] aiType = new int[cTypes];
            for (int i = 0; i < cTypes; ++i)
                {
                aiType[i] = readIndex(in);
                }
            m_aiTypeParams = aiType;
            }
        }

    @Override
    protected void resolveConstants()
        {
        ConstantPool pool = getConstantPool();

        m_constType = (TypeConstant) pool.getConstant(m_iType);

        if (m_aiTypeParams == null)
            {
            m_atypeParams = ConstantPool.NO_TYPES;
            }
        else
            {
            int            cParams     = m_aiTypeParams.length;
            TypeConstant[] atypeParams = new TypeConstant[cParams];
            for (int i = 0; i < cParams; ++i)
                {
                atypeParams[i] = (TypeConstant) pool.getConstant(m_aiTypeParams[i]);
                }
            m_atypeParams  = atypeParams;
            m_aiTypeParams = null;
            }
        }


    // ----- TypeConstant methods ------------------------------------------------------------------

    @Override
    public boolean isModifyingType()
        {
        return true;
        }

    @Override
    public TypeConstant getUnderlyingType()
        {
        return m_constType;
        }

    @Override
    public boolean isParamsSpecified()
        {
        return true;
        }

    @Override
    public List<TypeConstant> getParamTypes()
        {
        return m_atypeParams.length == 0
                ? Collections.EMPTY_LIST
                : Arrays.asList(m_atypeParams);
        }

    @Override
    public TypeConstant[] getParamTypesArray()
        {
        return m_atypeParams;
        }

    @Override
    protected TypeConstant getGenericParamType(String sName, List<TypeConstant> listParams)
        {
        assert listParams.isEmpty();
        return super.getGenericParamType(sName, getParamTypes());
        }

    @Override
    public boolean isExplicitClassIdentity(boolean fAllowParams)
        {
        return fAllowParams && getUnderlyingType().isExplicitClassIdentity(false);
        }

    @Override
    public boolean isAutoNarrowing(boolean fAllowVirtChild)
        {
        if (m_constType.isAutoNarrowing(fAllowVirtChild))
            {
            return true;
            }

        for (int i = 0, c = m_atypeParams.length; i < c; ++i)
            {
            if (m_atypeParams[i].isAutoNarrowing(fAllowVirtChild))
                {
                return true;
                }
            }

        return false;
        }

    @Override
    public TypeConstant getExplicitClassInto()
        {
        TypeConstant constResolved = m_constType.getExplicitClassInto();

        return constResolved.isParamsSpecified()
            ? constResolved.resolveGenerics(getConstantPool(), this)
            : constResolved;
        }

    @Override
    public TypeConstant resolveTypedefs()
        {
        TypeConstant constOriginal = m_constType;
        TypeConstant constResolved = constOriginal.resolveTypedefs();
        boolean      fDiff         = constOriginal != constResolved;

        if (constResolved.isParamsSpecified())
            {
            // TODO: this needs to be logged
            System.out.println("Unexpected type parameters for " + constOriginal.getValueString());
            return constResolved;
            }

        TypeConstant[] aconstOriginal = m_atypeParams;
        TypeConstant[] aconstResolved = aconstOriginal;
        for (int i = 0, c = aconstOriginal.length; i < c; ++i)
            {
            TypeConstant constParamOriginal = aconstOriginal[i];
            TypeConstant constParamResolved = constParamOriginal.resolveTypedefs();
            if (constParamOriginal != constParamResolved)
                {
                if (aconstResolved == aconstOriginal)
                    {
                    aconstResolved = aconstOriginal.clone();
                    }
                aconstResolved[i] = constParamResolved;
                fDiff = true;
                }
            }

        return fDiff
                ? getConstantPool().ensureParameterizedTypeConstant(constResolved, aconstResolved)
                : this;
        }

    @Override
    public void bindTypeParameters(MethodConstant idMethod)
        {
        for (TypeConstant typeParam : m_atypeParams)
            {
            typeParam.bindTypeParameters(idMethod);
            }
        }

    @Override
    public TypeConstant resolveGenerics(ConstantPool pool, GenericTypeResolver resolver)
        {
        if (resolver.equals(m_resolver))
            {
            return this;
            }

        TypeConstant constOriginal = m_constType;
        TypeConstant constResolved = constOriginal.resolveGenerics(pool, resolver);
        boolean      fDiff         = constOriginal != constResolved;

        assert !constResolved.isParamsSpecified();

        TypeConstant[] aconstOriginal = m_atypeParams;
        TypeConstant[] aconstResolved = aconstOriginal;
        for (int i = 0, c = aconstOriginal.length; i < c; ++i)
            {
            TypeConstant constParamOriginal = aconstOriginal[i];
            TypeConstant constParamResolved = constParamOriginal.resolveGenerics(pool, resolver);
            if (constParamOriginal != constParamResolved)
                {
                if (aconstResolved == aconstOriginal)
                    {
                    aconstResolved = aconstOriginal.clone();
                    }
                aconstResolved[i] = constParamResolved;
                fDiff = true;
                }
            }

        if (fDiff)
            {
            ParameterizedTypeConstant typeResolved = (ParameterizedTypeConstant)
                pool.ensureParameterizedTypeConstant(constResolved, aconstResolved);
            typeResolved.m_resolver = resolver;
            return typeResolved;
            }
        return this;
        }

    @Override
    public TypeConstant resolveConstraints(ConstantPool pool)
        {
        TypeConstant constOriginal = m_constType;
        TypeConstant constResolved = constOriginal.resolveConstraints(pool);
        boolean      fDiff         = constOriginal != constResolved;

        assert !constResolved.isParamsSpecified();

        TypeConstant[] aconstOriginal = m_atypeParams;
        TypeConstant[] aconstResolved = aconstOriginal;
        for (int i = 0, c = aconstOriginal.length; i < c; ++i)
            {
            TypeConstant constParamOriginal = aconstOriginal[i];
            TypeConstant constParamResolved = constParamOriginal.resolveConstraints(pool);
            if (constParamOriginal != constParamResolved)
                {
                if (aconstResolved == aconstOriginal)
                    {
                    aconstResolved = aconstOriginal.clone();
                    }
                aconstResolved[i] = constParamResolved;
                fDiff = true;
                }
            }

        if (fDiff)
            {
            ParameterizedTypeConstant typeResolved = (ParameterizedTypeConstant)
                pool.ensureParameterizedTypeConstant(constResolved, aconstResolved);
            return typeResolved;
            }
        return this;
        }

    @Override
    public boolean containsFormalType()
        {
        for (int i = 0, c = m_atypeParams.length; i < c; ++i)
            {
            if (m_atypeParams[i].containsFormalType())
                {
                return true;
                }
            }

        return false;
        }

    @Override
    public boolean containsGenericType()
        {
        for (int i = 0, c = m_atypeParams.length; i < c; ++i)
            {
            if (m_atypeParams[i].containsGenericType())
                {
                return true;
                }
            }

        return false;
        }

    @Override
    public boolean containsTypeParameter()
        {
        for (int i = 0, c = m_atypeParams.length; i < c; ++i)
            {
            if (m_atypeParams[i].containsTypeParameter())
                {
                return true;
                }
            }

        return false;
        }

    @Override
    public TypeConstant adoptParameters(ConstantPool pool, TypeConstant[] atypeParams)
        {
        return m_constType.adoptParameters(pool, atypeParams == null ? m_atypeParams : atypeParams);
        }

    @Override
    public TypeConstant resolveAutoNarrowing(ConstantPool pool, boolean fRetainParams, TypeConstant typeTarget)
        {
        // there are a number of scenarios to consider here;
        // let's assume there a class C<T>, which has a number of auto-narrowing methods:
        //  1) C f1(); // same as C<T> f1();
        //  2) immutable C f2();
        //  3) C<X> f3();
        //  4) A<C> f4();
        //  5) A<C<X>> f4();
        // and this type represents the return type of that method.
        //
        // Let's say that there is a class D<U> that extends (or mixes into) C<U>;
        // then we are expecting the following results:
        //
        //  scenario/this  | target | result
        // --------------------------------------------------------------
        //     1a) C       |   D    |   D
        //     2a) imm C   |   D    |   imm D
        //     3a) C<X>    |   D    |   D<Y> where Y is resolved against C<X>
        //     4a) A<C>    |   D    |   A<D>
        //     5a) A<C<X>> |   D    |   A<D<Y>> where Y is resolved against C<X>
        //                 |        |
        //     1b) C       |   D<Z> |   D<Z>
        //     2b) imm C   |   D<Z> |   imm D<Z>
        //     3b) C<X>    |   D<Z> |   D<Y> where Y is resolved against C<Z>
        //     4b) A<C>    |   D<Z> |   A<D<Z>>
        //     5b) A<C<X>> |   D<Z> |   A<D<Y>> where Y is resolved against C<Z>
        //
        // Scenarios 1 and 2 are dealt by the TerminalTypeConstant and ImmutableTypeConstant,
        // so here we only need to deal with 3, 4 and 5

        TypeConstant constOriginal = m_constType;
        TypeConstant constResolved = constOriginal.resolveAutoNarrowing(pool, fRetainParams, typeTarget);

        if (fRetainParams)
            {
            return constOriginal == constResolved
                    ? this
                    : pool.ensureParameterizedTypeConstant(constResolved, m_atypeParams);
            }

        if (constOriginal == constResolved)
            {
            // scenarios 4, 5
            boolean        fDiff          = false;
            TypeConstant[] aconstOriginal = m_atypeParams;
            TypeConstant[] aconstResolved = aconstOriginal;
            for (int i = 0, c = aconstOriginal.length; i < c; ++i)
                {
                TypeConstant constParamOriginal = aconstOriginal[i];
                TypeConstant constParamResolved = constParamOriginal.resolveAutoNarrowing(pool, false, typeTarget);
                if (constParamOriginal != constParamResolved)
                    {
                    if (aconstResolved == aconstOriginal)
                        {
                        aconstResolved = aconstOriginal.clone();
                        }
                    aconstResolved[i] = constParamResolved;
                    fDiff = true;
                    }
                }

            return fDiff
                    ? pool.ensureParameterizedTypeConstant(constOriginal, aconstResolved)
                    : this;
            }
        else
            {
            if (constResolved.isParamsSpecified())
                {
                // scenario 3b
                boolean        fDiff          = false;
                TypeConstant[] aconstOriginal = constResolved.getParamTypesArray();
                TypeConstant[] aconstResolved = aconstOriginal;
                for (int i = 0, c = aconstOriginal.length; i < c; ++i)
                    {
                    TypeConstant constParamOriginal = aconstOriginal[i];
                    TypeConstant constParamResolved = constParamOriginal.resolveGenerics(pool, this);
                    if (constParamOriginal != constParamResolved)
                        {
                        if (aconstResolved == aconstOriginal)
                            {
                            aconstResolved = aconstOriginal.clone();
                            }
                        aconstResolved[i] = constParamResolved;
                        fDiff = true;
                        }
                    }

                return fDiff
                        ? constResolved.adoptParameters(pool, aconstResolved)
                        : constResolved;
                }
            else
                {
                // scenario 3a
                if (constResolved.isSingleUnderlyingClass(true))
                    {
                    IdentityConstant idClz = constResolved.getSingleUnderlyingClass(true);
                    ClassStructure   clz   = (ClassStructure) idClz.getComponent();
                    return clz.isParameterized()
                            ? pool.ensureParameterizedTypeConstant(constResolved, m_atypeParams)
                            : constResolved;
                    }

                // TODO: how to figure out a case of the resolved type not being congruent
                //       to the original type and not parameterizable by our parameters?
                return constResolved;
                }
            }
        }

    @Override
    public boolean isNarrowedFrom(TypeConstant typeSuper, TypeConstant typeCtx)
        {
        assert typeSuper.isAutoNarrowing();

        // there is one exception from the "congruent topology" requirement:
        //  this  = immutable Tuple<ElementTypes>
        //  super = this:FixedSizeAble
        if (typeSuper instanceof TerminalTypeConstant)
            {
            return m_constType.isNarrowedFrom(typeSuper, typeCtx);
            }

        if (!(typeSuper instanceof ParameterizedTypeConstant))
            {
            return false;
            }

        ParameterizedTypeConstant that = (ParameterizedTypeConstant) typeSuper;

        TypeConstant constThis = this.m_constType;
        TypeConstant constThat = that.m_constType;

        if (constThat.isAutoNarrowing())
            {
            // the base is auto-narrowing; don't need to check the type parameters
            return constThis.isNarrowedFrom(constThat, typeCtx);
            }

        if (!constThis.isA(constThat))
            {
            return false;
            }

        TypeConstant[] aconstThis = this.m_atypeParams;
        TypeConstant[] aconstThat = that.m_atypeParams;
        for (int i = 0, c = aconstThis.length; i < c; ++i)
            {
            constThis = aconstThis[i];
            constThat = aconstThat[i];

            if (constThat.isAutoNarrowing() && !constThis.isNarrowedFrom(constThat, typeCtx))
                {
                return false;
                }
            else if (!constThis.isA(constThat))
                {
                return false;
                }
            }

        return true;
        }

    @Override
    public TypeConstant resolveTypeParameter(TypeConstant typeActual, String sFormalName)
        {
        typeActual = typeActual.resolveTypedefs();

        // unroll the actual type down to the parameterized type
        while (true)
            {
            if (!typeActual.isModifyingType())
                {
                return null;
                }

            if (typeActual.getFormat() == Format.ParameterizedType)
                {
                break;
                }
            typeActual = typeActual.getUnderlyingType();
            }

        // now simply recurse over the parameter types
        ParameterizedTypeConstant that = (ParameterizedTypeConstant) typeActual;

        // the underlying terminal type for actual type must fit the formal type
        ConstantPool pool         = ConstantPool.getCurrentPool();
        TypeConstant typeTermThis = this.m_constType;
        TypeConstant typeTermThat = that.m_constType;
        if (!typeTermThat.isA(typeTermThis))
            {
            return null;
            }

        TypeConstant[] aconstThis = this.m_atypeParams;
        TypeConstant[] aconstThat = that.m_atypeParams;

        for (int i = 0, c = Math.min(aconstThis.length, aconstThat.length); i < c; ++i)
            {
            TypeConstant typeResult = aconstThis[i].resolveTypeParameter(aconstThat[i], sFormalName);
            if (typeResult != null)
                {
                return typeResult;
                }
            }

        return null;
        }

    @Override
    protected TypeConstant cloneSingle(ConstantPool pool, TypeConstant type)
        {
        return pool.ensureParameterizedTypeConstant(type, m_atypeParams);
        }

    @Override
    protected TypeInfo buildTypeInfo(ErrorListener errs)
        {
        if (m_constType.isTuple())
            {
            // for the TypeInfo purposes the content of the type parameters is irrelevant
            return m_constType.ensureTypeInfoInternal(errs);
            }
        return super.buildTypeInfo(errs);
        }


    // ----- type comparison support --------------------------------------------------------------

    @Override
    protected Set<SignatureConstant> isInterfaceAssignableFrom(TypeConstant typeRight, Access accessLeft,
                                                               List<TypeConstant> listLeft)
        {
        assert listLeft.isEmpty();
        return super.isInterfaceAssignableFrom(typeRight, accessLeft, getParamTypes());
        }

    @Override
    public Usage checkConsumption(String sTypeName, Access access, List<TypeConstant> listParams)
        {
        assert listParams.isEmpty();

        ConstantPool pool = getConstantPool();
        if (m_constType.equals(pool.typeFunction()))
            {
            // function is special; we can disregard the Tuple holder
            TypeConstant[] atypeParams = pool.extractFunctionParams(this);
            if (atypeParams != null)
                {
                for (TypeConstant typeParam : atypeParams)
                    {
                    if (typeParam.producesFormalType(sTypeName, access))
                        {
                        return Usage.YES;
                        }
                    }
                }

            TypeConstant[] atypeReturns = pool.extractFunctionReturns(this);
            if (atypeReturns != null)
                {
                for (TypeConstant typeReturn : atypeReturns)
                    {
                    if (typeReturn.consumesFormalType(sTypeName, access))
                        {
                        return Usage.YES;
                        }
                    }
                }
            return Usage.NO;
            }
        return super.checkConsumption(sTypeName, access, getParamTypes());
        }

    @Override
    public Usage checkProduction(String sTypeName, Access access, List<TypeConstant> listParams)
        {
        assert listParams.isEmpty();

        ConstantPool pool = getConstantPool();
        if (m_constType.equals(pool.typeFunction()))
            {
            // function is special; we can disregard the Tuple holder
            TypeConstant[] atypeParams = pool.extractFunctionParams(this);
            if (atypeParams != null)
                {
                for (TypeConstant typeParam : atypeParams)
                    {
                    if (typeParam.consumesFormalType(sTypeName, access))
                        {
                        return Usage.YES;
                        }
                    }
                }

            TypeConstant[] atypeReturns = pool.extractFunctionReturns(this);
            if (atypeReturns != null)
                {
                for (TypeConstant typeReturn : atypeReturns)
                    {
                    if (typeReturn.producesFormalType(sTypeName, access))
                        {
                        return Usage.YES;
                        }
                    }
                return Usage.NO;
                }
            }
        return super.checkProduction(sTypeName, access, getParamTypes());
        }

    @Override
    public boolean containsSubstitutableMethod(SignatureConstant signature, Access access,
                                               boolean fFunction, List<TypeConstant> listParams)
        {
        assert listParams.isEmpty();
        return super.containsSubstitutableMethod(signature, access, fFunction, getParamTypes());
        }

    @Override
    public boolean isConstant()
        {
        for (TypeConstant type : m_atypeParams)
            {
            if (!type.isConstant())
                {
                return false;
                }
            }

        return super.isConstant();
        }

    @Override
    public boolean isNullable()
        {
        assert !m_constType.isNullable();
        return false;
        }

    // ----- Constant methods ----------------------------------------------------------------------

    @Override
    public Format getFormat()
        {
        return Format.ParameterizedType;
        }

    @Override
    public boolean containsUnresolved()
        {
        if (m_constType.containsUnresolved())
            {
            return true;
            }
        for (Constant param : m_atypeParams)
            {
            if (param.containsUnresolved())
                {
                return true;
                }
            }
        return false;
        }

    @Override
    public void forEachUnderlying(Consumer<Constant> visitor)
        {
        visitor.accept(m_constType);
        for (Constant param : m_atypeParams)
            {
            visitor.accept(param);
            }
        }

    @Override
    protected Object getLocator()
        {
        return m_atypeParams.length == 0
                ? m_constType
                : null;
        }

    @Override
    protected int compareDetails(Constant obj)
        {
        if (!(obj instanceof ParameterizedTypeConstant))
            {
            return -1;
            }

        ParameterizedTypeConstant that = (ParameterizedTypeConstant) obj;
        int n = this.m_constType.compareTo(that.m_constType);
        if (n == 0)
            {
            TypeConstant[] atypeThis = this.m_atypeParams;
            TypeConstant[] atypeThat = that.m_atypeParams;
            for (int i = 0, c = Math.min(atypeThis.length, atypeThat.length); i < c; ++i)
                {
                n = atypeThis[i].compareTo(atypeThat[i]);
                if (n != 0)
                    {
                    return n;
                    }
                }
            n = atypeThis.length - atypeThat.length;
            }
        return n;
        }

    @Override
    public String getValueString()
        {
        StringBuilder sb = new StringBuilder();

        sb.append(m_constType.getValueString())
          .append('<');

        boolean first = true;
        for (TypeConstant type : m_atypeParams)
            {
            if (first)
                {
                first = false;
                }
            else
                {
                sb.append(", ");
                }
            sb.append(type.getValueString());
            }

        sb.append('>');

        return sb.toString();
        }

    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void registerConstants(ConstantPool pool)
        {
        m_constType   = (TypeConstant) pool.register(m_constType);
        m_atypeParams = (TypeConstant[]) registerConstants(pool, m_atypeParams);
        }

    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        out.writeByte(getFormat().ordinal());
        writePackedLong(out, indexOf(m_constType));
        writePackedLong(out, m_atypeParams.length);
        for (TypeConstant constType : m_atypeParams)
            {
            writePackedLong(out, constType.getPosition());
            }
        }

    @Override
    public boolean validate(ErrorListener errs)
        {
        if (!isValidated())
            {
            boolean fHalt = false;

            for (TypeConstant type : m_atypeParams)
                {
                fHalt |= type.validate(errs);
                }

            // a parameterized type constant has to be followed by a terminal type constant
            // specifying a class/interface identity
            if (!(m_constType.resolveTypedefs()).isExplicitClassIdentity(false))
                {
                log(errs, Severity.ERROR, VE_PARAM_TYPE_ILLEGAL, m_constType.getValueString());
                fHalt = true;
                }

            if (!fHalt)
                {
                return super.validate(errs);
                }
            }

        return false;
        }


    // ----- Object methods ------------------------------------------------------------------------

    @Override
    public int hashCode()
        {
        int n = m_constType.hashCode() + m_atypeParams.length;
        for (TypeConstant type : m_atypeParams)
            {
            n ^= type.hashCode();
            }
        return n;
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * During disassembly, this holds the index of the underlying TypeConstant.
     */
    private transient int m_iType;

    /**
     * During disassembly, this holds the index of the the type parameters.
     */
    private transient int[] m_aiTypeParams;

    /**
     * The underlying TypeConstant.
     */
    private TypeConstant m_constType;

    /**
     * The type parameters.
     */
    private TypeConstant[] m_atypeParams;

    /**
     * The last resolver that was used by the generic type resolution that produced this type.
     * <p/>
     * When we're building the TypeInfo for a given type, we repetitively resolve contributions'
     * property types and method signatures against the type for witch the TypeInfo is built.
     * In a number of cases it introduces a problem of "double-dipping" into the resolution logic,
     * especially when compiler creates formal synthetic relational types due to type inference
     * (context specific type narrowing).
     * <p/>
     * To avoid keeping track of such a double-resolution, it's desirable to make sure the generic
     * type resolution is as idempotent as possible. However, We need to be aware that it's quite
     * natural to have to resolve a given method signature a couple of times using different
     * resolvers.
     * <p/>
     * An idempotency solution we utilize here is to keep track of the last resolver used to resolve
     * a given formal type and use it to prevent double-dipping.
     */
    private transient GenericTypeResolver m_resolver;
    }
