package org.xvm.asm;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.function.Predicate;

import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.IdentityConstant.NestedIdentity;
import org.xvm.asm.constants.IntersectionTypeConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.MethodInfo;
import org.xvm.asm.constants.NativeRebaseConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.PropertyInfo;
import org.xvm.asm.constants.SignatureConstant;
import org.xvm.asm.constants.StringConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.TypeConstant.Relation;
import org.xvm.asm.constants.TypeInfo;
import org.xvm.asm.constants.TypeParameterConstant;
import org.xvm.asm.constants.UnresolvedNameConstant;
import org.xvm.asm.constants.UnresolvedTypeConstant;

import org.xvm.asm.op.Call_01;
import org.xvm.asm.op.Invoke_00;
import org.xvm.asm.op.Invoke_01;
import org.xvm.asm.op.Invoke_0N;
import org.xvm.asm.op.Invoke_10;
import org.xvm.asm.op.Invoke_11;
import org.xvm.asm.op.Invoke_1N;
import org.xvm.asm.op.Invoke_N0;
import org.xvm.asm.op.Invoke_N1;
import org.xvm.asm.op.Invoke_NN;
import org.xvm.asm.op.L_Get;
import org.xvm.asm.op.L_Set;
import org.xvm.asm.op.Return_0;
import org.xvm.asm.op.Return_1;
import org.xvm.asm.op.Return_N;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle.GenericHandle;
import org.xvm.runtime.TypeComposition;
import org.xvm.runtime.Utils;

import org.xvm.runtime.template.xRef.RefHandle;

import org.xvm.util.ListMap;


/**
 * An XVM Structure that represents an entire Class. This is also the base class for module and
 * package structures.
 */
public class ClassStructure
        extends Component
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a ClassStructure with the specified identity.
     *
     * @param xsParent   the XvmStructure that contains this structure
     * @param nFlags     the Component bit flags
     * @param constId    the constant that specifies the identity of the Module, Package, or Class
     * @param condition  the optional condition for this ClassStructure
     */
    protected ClassStructure(XvmStructure xsParent, int nFlags, IdentityConstant constId,
                             ConditionalConstant condition)
        {
        super(xsParent, nFlags, constId, condition);
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return true iff this class is a singleton
     */
    public boolean isSingleton()
        {
        switch (getFormat())
            {
            case MODULE:
            case PACKAGE:
            case ENUMVALUE:
                // these types are always singletons
                return true;

            case INTERFACE:
            case CLASS:
            case ENUM:
            case MIXIN:
                // these types are never singletons
                return false;

            case CONST:
            case SERVICE:
                // these COULD be singletons (if they are static and NOT an inner class)
                if (isStatic())
                    {
                    Format format = getParent().getFormat();
                    return format == Format.MODULE || format == Format.PACKAGE;
                    }
                return false;

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * Check if this class is annotated as "Abstract".
     *
     * Note: class structures are never marked with the {@link #isAbstract abstract} flag and
     * can only be made abstract via the annotation.
     *
     * @return true iff this class is annotated as Abstract
     */
    public boolean isExplicitlyAbstract()
        {
        for (Contribution contrib : getContributionsAsList())
            {
            if (contrib.getComposition() == Composition.Incorporates)
                {
                TypeConstant type = contrib.getTypeConstant();

                if (type.isSingleUnderlyingClass(false) &&
                    type.getSingleUnderlyingClass(false).equals(getConstantPool().clzAbstract()))
                    {
                    return true;
                    }
                }
            }
        return false;
        }

    /**
     * @return true iff this class is a module, package, or class whose immediate parent is a module
     *         or package
     */
    public boolean isTopLevel()
        {
        switch (getFormat())
            {
            case MODULE:
            case PACKAGE:
                return true;

            case INTERFACE:
            case CLASS:
            case ENUM:
            case MIXIN:
            case CONST:
            case SERVICE:
                {
                Format format = getParent().getFormat();
                return format == Format.MODULE || format == Format.PACKAGE;
                }

            case ENUMVALUE:
                // enum values are always a child of an enum
                return false;

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * @return true iff this class is an inner class
     */
    public boolean isInnerClass()
        {
        return !isTopLevel();
        }

    /**
     * Note: A virtual child class is a child class that is instantiated using the "NEWC_*" op codes.
     *
     * @return true iff this class is a virtual child class
     */
    public boolean isVirtualChild()
        {
        switch (getFormat())
            {
            case MODULE:
            case PACKAGE:
            case ENUM:
            case ENUMVALUE:
                return false;

            case INTERFACE:
            case MIXIN:
            case CLASS:
            case CONST:
            case SERVICE:
                {
                if (isSynthetic() || isStatic())
                    {
                    // anonymous and static child classes are not virtual
                    return false;
                    }

                Component parent = getParent();
                Format    format = parent.getFormat();
                while (format == Format.PROPERTY)
                    {
                    parent = parent.getParent();
                    format = parent.getFormat();
                    }
                // neither a top-level class nor a local class inside a method are considered child
                // classes
                return format != Format.MODULE && format != Format.PACKAGE && format != Format.METHOD;
                }

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * @return the parent class for this virtual child, or null
     */
    public ClassStructure getVirtualParent()
        {
        if (!isVirtualChild())
            {
            return null;
            }

        Component parent = getParent();
        while (true)
            {
            switch (parent.getFormat())
                {
                case MODULE:
                case PACKAGE:
                case METHOD:
                    return null;

                case ENUM:
                case ENUMVALUE:
                case INTERFACE:
                case CLASS:
                case MIXIN:
                case CONST:
                case SERVICE:
                    return (ClassStructure) parent;

                case PROPERTY:
                    parent = parent.getParent();
                    break;

                case FILE:
                case TYPEDEF:
                case MULTIMETHOD:
                default:
                    throw new IllegalStateException();
                }
            }
        }

    /**
     * @return true iff this class is an anonymous inner class
     */
    public boolean isAnonInnerClass()
        {
        return isInnerClass() && isSynthetic()
                && getIdentityConstant().getParentConstant() instanceof MethodConstant;
        }

    /**
     * @return true iff this is an inner class with a reference to an "outer this"
     */
    public boolean hasOuter()
        {
        // a class that is static can not have an outer
        // a class that is NOT an inner class can not have an outer (i.e. MUST be an inner class)
        if (isStatic() || !isInnerClass())
            {
            return false;
            }

        // if this class is nested directly under the outer class, then it does have an outer, but
        // if there are properties and/or methods interposed between the outer class and this class,
        // then each of those interposed components MUST be non-static in order for this class to
        // have an outer reference
        Component parent = getParent();
        while (true)
            {
            switch (parent.getFormat())
                {
                case MULTIMETHOD:
                    // ignore multi-methods; only properties and methods matter in this
                    // determination
                    break;

                case METHOD:
                case PROPERTY:
                    if (parent.isStatic())
                        {
                        return false;
                        }
                    break;

                case INTERFACE:
                case CLASS:
                case CONST:
                case SERVICE:
                case ENUM:
                case ENUMVALUE:
                case MIXIN:
                    return true;

                default:
                    throw new IllegalStateException(
                        parent.getIdentityConstant() + " format=" + parent.getFormat());
                }

            parent = parent.getParent();
            }
        }

    /**
     * @return an "outer this" class structure
     */
    public ClassStructure getOuter()
        {
        assert hasOuter();

        Component parent = getParent();
        while (true)
            {
            switch (parent.getFormat())
                {
                case MULTIMETHOD:
                case METHOD:
                case PROPERTY:
                    break;

                case INTERFACE:
                case CLASS:
                case CONST:
                case SERVICE:
                case ENUM:
                case ENUMVALUE:
                case MIXIN:
                    return (ClassStructure) parent;

                default:
                    throw new IllegalStateException(
                        parent.getIdentityConstant() + " format=" + parent.getFormat());
                }

            parent = parent.getParent();
            }
        }

    /**
     * @return true iff this class is a Const
     */
    public boolean isConst()
        {
        switch (getFormat())
            {
            case PACKAGE:
            case CONST:
            case ENUM:
            case ENUMVALUE:
            case MODULE:
                return true;

            default:
                return false;
            }
        }

    /**
     * Get a virtual child class by the specified name on this class or any of its contributions.
     *
     * @param sName  the child class name
     *
     * @return a child structure or null if not found
     */
    public ClassStructure getVirtualChild(String sName)
        {
        return getVirtualChildImpl(sName, true);
        }

    /**
     * Implementation of {@link #getVirtualChild(String)}
     */
    private ClassStructure getVirtualChildImpl(String sName, boolean fAllowInto)
        {
        Component child = getChild(sName);
        if (child instanceof ClassStructure)
            {
            ClassStructure clzChild = (ClassStructure) child;
            return clzChild.isVirtualChild() ? clzChild : null;
            }

        if (child != null)
            {
            // not a class
            return null;
            }

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();

            if (   typeContrib.containsUnresolved()
               || !typeContrib.isSingleUnderlyingClass(true)) // disregard relational type contributions
                {
                continue;
                }

            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        break;
                        }
                    // fall through
                case Annotation:
                case Implements:
                case Incorporates:
                case Extends:
                    {
                    ClassStructure clzContrib = (ClassStructure)
                            typeContrib.getSingleUnderlyingClass(true).getComponent();
                    ClassStructure clzChild = clzContrib.getVirtualChildImpl(sName, false);
                    if (clzChild != null)
                        {
                        return clzChild;
                        }
                    break;
                    }

                default:
                    // ignore any other contributions
                    break;
                }
            }
        return null;
        }

    /**
     * @return the number of type parameters for this class
     */
    public int getTypeParamCount()
        {
        Map mapThis = m_mapParams;
        return mapThis == null
                ? 0
                : mapThis.size();
        }

    /**
     * Obtain the type parameters for the class as an ordered read-only map, keyed by name and with
     * a corresponding value of the type constraint for the parameter.
     *
     * @return a read-only map of type parameter name to type
     */
    public ListMap<StringConstant, TypeConstant> getTypeParams()
        {
        ListMap<StringConstant, TypeConstant> mapThis = m_mapParams;
        return mapThis == null
                ? ListMap.EMPTY
                : mapThis;
        }

    /**
     * Obtain the type parameters for the class as a list of map entries from name to type.
     *
     * @return a read-only list of map entries from type parameter name to type
     */
    public List<Map.Entry<StringConstant, TypeConstant>> getTypeParamsAsList()
        {
        ListMap<StringConstant, TypeConstant> mapThis = m_mapParams;
        return mapThis == null
                ? Collections.EMPTY_LIST
                : mapThis.asList();
        }

    /**
     * Add a type parameter.
     *
     * @param sName            the type parameter name
     * @param typeConstraint   the type parameter constraint type
     */
    public void addTypeParam(String sName, TypeConstant typeConstraint)
        {
        ListMap<StringConstant, TypeConstant> map = m_mapParams;
        if (map == null)
            {
            m_mapParams = map = new ListMap<>();
            }

        ConstantPool pool = getConstantPool();

        // check for turtles, for example: "ElementTypes extends Tuple<ElementTypes>"
        if (typeConstraint.getParamsCount() == 1 &&
                typeConstraint.isTuple() &&
                typeConstraint.getParamTypesArray()[0].getValueString().equals(sName))
            {
            typeConstraint = pool.ensureTypeSequenceTypeConstant();
            }
        map.put(pool.ensureStringConstant(sName), typeConstraint);

        // each type parameter also has a synthetic property of the same name,
        // whose type is of type"Type<constraint-type>"
        TypeConstant typeConstraintType = pool.ensureClassTypeConstant(
            pool.clzType(), null, typeConstraint);

        // create the property and mark it as a type parameter
        createProperty(false, Access.PUBLIC, Access.PUBLIC, typeConstraintType, sName)
            .markAsGenericTypeParameter();
        markModified();
        }

    /**
     * @return true if this class is parameterized (generic)
     */
    public boolean isParameterized()
        {
        return m_mapParams != null;
        }

    /**
     * @return the formal type (e.g. Map<KeyType, ValueType>)
     */
    public TypeConstant getFormalType()
        {
        TypeConstant typeFormal = m_typeFormal;
        if (typeFormal == null)
            {
            ConstantPool     pool        = getConstantPool();
            IdentityConstant constantClz = getIdentityConstant();

            if (isVirtualChild())
                {
                TypeConstant typeParent = ((ClassStructure) getParent()).getFormalType();
                typeFormal = pool.ensureVirtualChildTypeConstant(typeParent, getName());
                }
            else
                {
                typeFormal = constantClz.getType();
                }

            if (isParameterized())
                {
                Map<StringConstant, TypeConstant> mapThis = m_mapParams;
                TypeConstant[] aTypes = new TypeConstant[mapThis.size()];
                int ix = 0;
                for (StringConstant constName : mapThis.keySet())
                    {
                    PropertyStructure prop = (PropertyStructure) getChild(constName.getValue());
                    aTypes[ix++] = prop.getIdentityConstant().getFormalType();
                    }

                typeFormal = pool.ensureParameterizedTypeConstant(typeFormal, aTypes);
                }

            m_typeFormal = typeFormal;
            }
        return typeFormal;
        }

    /**
     * @return the canonical type (e.g. Map<Object, Object>)
     */
    public TypeConstant getCanonicalType()
        {
        TypeConstant typeCanonical = m_typeCanonical;
        if (typeCanonical == null)
            {
            ConstantPool     pool     = getConstantPool();
            IdentityConstant constClz = getIdentityConstant();

            if (constClz.equals(pool.clzTuple()))
                {
                // canonical Tuple
                return m_typeCanonical = pool.ensureParameterizedTypeConstant(pool.typeTuple());
                }

            if (isVirtualChild())
                {
                TypeConstant typeParent = ((ClassStructure) getParent()).getCanonicalType();
                typeCanonical = pool.ensureVirtualChildTypeConstant(typeParent, getName());
                }
            else
                {
                typeCanonical = constClz.getType();
                }

            if (isParameterized())
                {
                Map<StringConstant, TypeConstant> mapParams = getTypeParams();
                TypeConstant[] atypeParam = new TypeConstant[mapParams.size()];
                int ix = 0;
                GenericTypeResolver resolver = new SimpleTypeResolver(new ArrayList<>());
                for (TypeConstant typeParam : mapParams.values())
                    {
                    atypeParam[ix++] = typeParam.isFormalTypeSequence()
                            ? pool.ensureParameterizedTypeConstant(pool.typeTuple())
                            : typeParam.resolveGenerics(pool, resolver);
                    }
                typeCanonical = pool.ensureParameterizedTypeConstant(typeCanonical, atypeParam);
                }

            m_typeCanonical = typeCanonical;
            }
        return typeCanonical;
        }

    /**
     * Resolve the formal type for this class based on the specified list of actual types.
     *
     * Note: the specified list is allowed to skip some number of actual parameters (at the tail);
     *       they will be replaced by the corresponding resolved canonical types
     *
     * @param pool        the ConstantPool to place a potentially created new constant into
     * @param listActual  the list of actual types
     *
     * @return the resolved type
     */
    public TypeConstant resolveType(ConstantPool pool, List<TypeConstant> listActual)
        {
        return listActual.isEmpty() && !isParameterized()
            ? getCanonicalType()
            : getFormalType().resolveGenerics(pool, new SimpleTypeResolver(listActual));
        }

    /**
     * If the specified list of actual parameters is missing some number of actual parameters,
     * add the corresponding resolved canonical types to the end of the list.
     *
     * @param pool        the ConstantPool to place a potentially created new constant into
     * @param listActual  the list of actual types
     *
     * @return a list of types that has exact size as the map of formal parameters for this class
     */
    public List<TypeConstant> normalizeParameters(ConstantPool pool, List<TypeConstant> listActual)
        {
        int cActual = listActual.size();
        int cFormal = getTypeParamCount();

        return cActual == cFormal
            ? listActual
            : resolveType(pool, listActual).getParamTypes();
        }

    /**
     * If the specified array of actual parameters is missing some number of actual parameters,
     * add the corresponding resolved canonical types to the end of the list.
     *
     * @param pool         the ConstantPool to place a potentially created new constant into
     * @param atypeActual  the array of actual types
     *
     * @return an array of types that has exact size as the map of formal parameters for this class
     */
    public TypeConstant[] normalizeParameters(ConstantPool pool, TypeConstant[] atypeActual)
        {
        int cActual = atypeActual.length;
        int cFormal = getTypeParamCount();

        return cActual == cFormal
            ? atypeActual
            : resolveType(pool, Arrays.asList(atypeActual)).getParamTypesArray();
        }


    // ----- Tuple support -------------------------------------------------------------------------

    /**
     * @return true iff this class is a Tuple or a Tuple mixin
     */
    public boolean isTuple()
        {
        if (getIdentityConstant().equals(getConstantPool().clzTuple()))
            {
            return true;
            }

        for (Contribution contrib : getContributionsAsList())
            {
            if (contrib.getComposition() == Composition.Into)
                {
                if (contrib.getTypeConstant().isTuple())
                    {
                    return true;
                    }
                }
            }
        return false;
        }

    /**
     * When this class represents an R-Value Tuple or a Tuple mixin, find a contribution that is
     * assignable to the specified L-Value Tuple type.
     *
     * @param tupleLeft  the L-Value Tuple type
     * @param listRight  the list of actual generic parameters for this class
     *
     * @return the relation
     */
    public Relation findTupleContribution(TypeConstant tupleLeft, List<TypeConstant> listRight)
        {
        if (getIdentityConstant().equals(tupleLeft.getSingleUnderlyingClass(true)))
            {
            return calculateAssignability(tupleLeft.getParamTypes(), Access.PUBLIC, listRight);
            }

        for (Contribution contrib : getContributionsAsList())
            {
            if (contrib.getComposition() == Composition.Into)
                {
                ConstantPool pool        = ConstantPool.getCurrentPool();
                TypeConstant typeContrib = contrib.resolveGenerics(pool, new SimpleTypeResolver(listRight));

                if (typeContrib != null && typeContrib.isTuple())
                    {
                    IdentityConstant idContrib  = typeContrib.getSingleUnderlyingClass(true);
                    ClassStructure   clzContrib = (ClassStructure) idContrib.getComponent();

                    return clzContrib.findTupleContribution(tupleLeft, typeContrib.getParamTypes());
                    }
                }
            }
        return Relation.INCOMPATIBLE;
        }

    /**
     * When this class represents a Tuple or a Tuple mixin, get the resulting Tuple type parameters
     *
     * @param listParams  the list of actual generic parameters for this class
     *
     * @return the list of types
     */
    public List<TypeConstant> getTupleParamTypes(List<TypeConstant> listParams)
        {
        ConstantPool pool = ConstantPool.getCurrentPool();

        if (getIdentityConstant().equals(pool.clzTuple()))
            {
            return listParams;
            }

        for (Contribution contrib : getContributionsAsList())
            {
            if (contrib.getComposition() == Composition.Into)
                {
                TypeConstant typeContrib = contrib.resolveGenerics(pool, new SimpleTypeResolver(listParams));
                if (typeContrib != null && typeContrib.isTuple())
                    {
                    return typeContrib.getTupleParamTypes();
                    }
                }
            }
        return Collections.EMPTY_LIST;
        }


    // ----- component methods ---------------------------------------------------------------------

    @Override
    public boolean isClassContainer()
        {
        return true;
        }

    @Override
    public boolean isMethodContainer()
        {
        return true;
        }

    @Override
    public boolean isAutoNarrowingAllowed()
        {
        // a class must not be a singleton
        if (isSingleton())
            {
            return false;
            }

        // inner classes inside a method (including anonymous inner classes) do not auto-narrow
        // because they are effectively final
        Component parent = getParent();
        if (parent instanceof MethodStructure)
            {
            return false;
            }

        // otherwise, assuming that this not the "outermost" class, keep asking up the parent chain
        return ((ClassConstant) getIdentityConstant()).getDepthFromOutermost() == 0
                || parent.isAutoNarrowingAllowed();
        }

    @Override
    public ResolutionResult resolveName(String sName, Access access, ResolutionCollector collector)
        {
        return resolveContributedName(sName, access, collector, true);
        }

    /**
     * Determine if the specified name is referring to a name introduced by any of the contributions
     * for this class.
     * <p/>
     * Note, that this method is used *before* the integrity of the structures is validated,
     * so must be ready for "infinite recursions", that will be reported later.
     *
     * @param sName       the name to resolve
     * @param access      the accessibility to use to determine if the name is visible
     * @param collector   the collector to which the potential name matches will be reported
     * @param fAllowInto  if false, the "into" contributions should not be looked at
     *
     * @return the resolution result is one of: RESOLVED, UNKNOWN or POSSIBLE
     */
    protected ResolutionResult resolveContributedName(
            String sName, Access access, ResolutionCollector collector, boolean fAllowInto)
        {
        Component child = getChild(sName);
        if (child != null && child.canBeSeen(access))
            {
            switch (child.getIdentityConstant().getFormat())
                {
                case Property:
                    {
                    PropertyStructure prop = (PropertyStructure) child;
                    if (!prop.isGenericTypeParameter() &&
                        !prop.isStatic())
                        {
                        // instance property should be resolved directly as well as methods
                        break;
                        }
                    }
                    // fall through
                case Module:
                case Package:
                case Class:
                case Typedef:
                    collector.resolvedComponent(child);
                    return ResolutionResult.RESOLVED;
                }
            return ResolutionResult.UNKNOWN;
            }

        // no child by that name; check if it was introduced by a contribution
        NextContribution: for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            if (typeContrib.containsUnresolved())
                {
                return ResolutionResult.POSSIBLE;
                }

            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        continue NextContribution;
                        }
                    access = access.minOf(Access.PROTECTED);
                    break;

                case Annotation:
                case Delegates:
                case Implements:
                    access = Access.PUBLIC;
                    break;

                case Extends:
                    access = access.minOf(Access.PROTECTED);
                    break;

                case Incorporates:
                    fAllowInto = false;
                    access = access.minOf(Access.PROTECTED);
                    break;

                default:
                    throw new IllegalStateException();
                }

            // because some of the components in the graph that we would need to visit in order to
            // answer the question about generic type parameters are not yet ready to answer those
            // questions, we rely instead on a virtual child's knowledge of its parents' type
            // parameters to short-circuit that search; we know that the virtual child type can
            // answer the question precisely because it exists (they are created no earlier than a
            // certain stage)
            if (typeContrib.isVirtualChild())
                {
                // check the paren't formal type
                TypeConstant typeFormal = typeContrib.getParentType().resolveGenericType(sName);
                if (typeFormal != null)
                    {
                    collector.resolvedConstant(typeFormal.getDefiningConstant());
                    return ResolutionResult.RESOLVED;
                    }
                }

            if (typeContrib.isSingleUnderlyingClass(true))
                {
                ClassStructure clzContrib =
                        (ClassStructure) typeContrib.getSingleUnderlyingClass(true).getComponent();

                if (m_fVisited)
                    {
                    // recursive contribution; it will be reported later
                    return ResolutionResult.UNKNOWN;
                    }

                m_fVisited = true;
                ResolutionResult result =
                        clzContrib.resolveContributedName(sName, access, collector, fAllowInto);
                m_fVisited = false;

                if (result != ResolutionResult.UNKNOWN)
                    {
                    return result;
                    }
                }
            else
                {
                return typeContrib.resolveContributedName(sName, collector);
                }
            }

        return ResolutionResult.UNKNOWN;
        }

    @Override
    protected ClassStructure cloneBody()
        {
        ClassStructure that = (ClassStructure) super.cloneBody();

        // deep-clone the parameter list information (since the structure is mutable)
        if (this.m_mapParams != null)
            {
            ListMap<StringConstant, TypeConstant> mapThis = this.m_mapParams;
            ListMap<StringConstant, TypeConstant> mapThat = new ListMap<>();
            mapThat.putAll(mapThis);
            that.m_mapParams = mapThat;
            }

        return that;
        }


    // ----- type comparison support ---------------------------------------------------------------

    /**
     * Check if this class extends the specified class.
     *
     * @param idClass  the class to test if this class represents an extension of
     *
     * @return true if this type represents a sub-classing of the specified class
     */
    public boolean extendsClass(IdentityConstant idClass)
        {
        if (idClass.equals(getConstantPool().clzObject()))
            {
            // everything is considered to extend Object (even interfaces)
            return true;
            }

        if (getFormat() == Format.INTERFACE)
            {
            // interfaces do not extend; they implement
            return false;
            }

        if (idClass.equals(getIdentityConstant()))
            {
            // while a class cannot technically extend itself, this does satisfy the "is-a" test
            return true;
            }

        ClassStructure structCur = this;
        NextSuper: while (true)
            {
            for (Contribution contrib : structCur.getContributionsAsList())
                {
                if (contrib.getComposition() == Composition.Extends)
                    {
                    // even though this class may be id'd using a ModuleConstant or PackageConstant,
                    // the super will always be a class (because a Module and a Package cannot be
                    // extended)
                    ClassConstant constSuper = (ClassConstant)
                        contrib.getTypeConstant().getSingleUnderlyingClass(false);
                    if (idClass.equals(constSuper))
                        {
                        return true;
                        }

                    structCur = (ClassStructure) constSuper.getComponent();
                    continue NextSuper;
                    }
                }

            return false;
            }
        }

    /**
     * Check if this class has the specified class as any of its contributions (recursively).
     *
     * @param idClass  the class to test if this class has a contribution of
     *
     * @return true if this type has a contribution of the specified class
     */
    public boolean hasContribution(IdentityConstant idClass, boolean fAllowInto)
        {
        if (idClass.equals(getConstantPool().clzObject()))
            {
            // everything is considered to contain the Object (even interfaces)
            return true;
            }

        if (idClass.equals(getIdentityConstant()))
            {
            return true;
            }

        return findContributionImpl(idClass, fAllowInto) != null;
        }

    /**
     * Find a contribution of a specified identity.
     *
     * @param idContrib   the contribution identity
     *
     * @return a first (if more than one) contribution matching the specified identity
     *         or null if none found
     */
    public Contribution findContribution(IdentityConstant idContrib)
        {
        if (idContrib.equals(getIdentityConstant()))
            {
            return new Contribution(Composition.Equal, getFormalType());
            }

        return findContributionImpl(idContrib, false);
        }

    /**
     * Implementation of the contribution lookup.
     *
     * @param idContrib   the contribution identity (must mot be this class's id)
     * @param fAllowInto  if false, ignore the Into contributions
     *
     * @return a first (if more than one) contribution matching the specified identity
     *         or null if none found
     */
    private Contribution findContributionImpl(IdentityConstant idContrib, boolean fAllowInto)
        {
        assert !idContrib.equals(getIdentityConstant());

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();

            if (   typeContrib.containsUnresolved()           // this question cannot be answered yet
               || !typeContrib.isSingleUnderlyingClass(true)) // disregard relational type contributions
                {
                continue;
                }

            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        break;
                        }
                    // fall through
                case Annotation:
                case Implements:
                case Incorporates:
                case Extends:
                case Delegates:
                    {
                    IdentityConstant idContribNext = typeContrib.getSingleUnderlyingClass(true);
                    if (idContribNext.equals(idContrib))
                        {
                        return contrib;
                        }
                    ClassStructure clzContrib  = (ClassStructure) idContribNext.getComponent();
                    Contribution   contribNext = clzContrib.findContributionImpl(idContrib, false);
                    if (contribNext != null)
                        {
                        return contribNext;
                        }
                    }

                default:
                    // ignore any other contributions
                    break;
                }
            }
        return null;
        }

    /**
     * @return true iff this class is an Exception
     */
    public boolean isException()
        {
        return extendsClass(getConstantPool().clzException());
        }

    /**
     * Determine the type to rebase onto. Note that a rebase type is NEVER parameterized.
     *
     * @return a type to rebase onto, if rebasing is required by this class; otherwise null
     */
    public TypeConstant getRebaseType()
        {
        ConstantPool pool   = getConstantPool();
        Format       format = getFormat();

        switch (format)
            {
            case MODULE:
                return pool.typeModuleRB();

            case PACKAGE:
                return pool.typePackageRB();

            case ENUM:
                return pool.typeEnumRB();

            case CONST:
            case SERVICE:
                // only if the format differs from the format of the super
                if (format != getSuper().getFormat())
                    {
                    return format == Format.CONST ? pool.typeConstRB() : pool.typeServiceRB();
                    }
                // break through
            default:
                return null;
            }
        }

    /**
     * @return the ClassStructure that this ClassStructure extends, or null if this ClassStructure
     *         is an interface or does not contain an Extends contribution
     */
    public ClassStructure getSuper()
        {
        Contribution contribExtends = findContribution(Composition.Extends);
        if (contribExtends != null)
            {
            TypeConstant typeExtends = contribExtends.getTypeConstant();
            if (typeExtends.isSingleUnderlyingClass(false))
                {
                return (ClassStructure) typeExtends.getSingleUnderlyingClass(false).getComponent();
                }
            }
        return null;
        }

    /**
     * For a "virtual child" ClassStructure component that is still being resolved during the
     * compilation process, determine the set of "virtual child super" classes and interfaces for
     * this virtual child.
     *
     * @param setContribs  the set to collect implicit contributions (extends/implements) in
     *
     * @return false iff some unresolved structure was encountered that may have prevented the
     *         determination of a virtual child super; the return valued does NOT imply the presence
     *         or the absence of a virtual child super
     */
    public boolean resolveVirtualSuper(Set<Contribution> setContribs)
        {
        assert isVirtualChild();

        // prevent circularity and repeatedly checking the same components
        Set<IdentityConstant> setVisited = new HashSet<>();
        IdentityConstant idThis = getIdentityConstant();
        setVisited.add(idThis);

        Component parent = getParent();
        int       cDepth = 1;
        while (true)
            {
            // avoid circular / repetitive checks
            if (setVisited.add(parent.getIdentityConstant()))
                {
                Iterator<IdentityConstant> iter = parent.potentialVirtualChildContributors();
                if (iter == null)
                    {
                    return false;
                    }
                while (iter.hasNext())
                    {
                    IdentityConstant idContrib = iter.next();
                    if (idContrib.containsUnresolved())
                        {
                        return false;
                        }

                    Component component = idContrib.getComponent();
                    if (component != null)
                        {
                        Object o = component.findVirtualChildSuper(idThis, cDepth, setVisited);
                        if (o != null)
                            {
                            if (o instanceof Boolean)
                                {
                                // something necessary hasn't resolved yet
                                assert !((Boolean) o);
                                return false;
                                }

                            // we found a virtual child super, which may be a super interface
                            // (implying "implements") or class (implying "extends"); the identity
                            // of the super is created relative to the contributed identity, instead
                            // of just using the identity of the class that we found, because the
                            // actual super type may be a phantom (it might not have a structure)
                            IdentityConstant idSuper   = idThis.appendTrailingPathTo(idContrib, cDepth);
                            Component        compSuper = idSuper.getComponent();
                            ClassStructure   clzSuper;
                            TypeConstant     typeSuper;
                            if (compSuper == null)
                                {
                                ConstantPool pool = getConstantPool();
                                clzSuper  = (ClassStructure) o;
                                typeSuper = pool.ensureVirtualChildTypeConstant(
                                        ((ClassStructure) component).getFormalType(), idThis.getName());
                                if (clzSuper.isParameterized())
                                    {
                                    typeSuper = typeSuper.adoptParameters(pool, clzSuper.getFormalType());
                                    }
                                }
                            else
                                {
                                clzSuper  = (ClassStructure) compSuper;
                                typeSuper = clzSuper.getFormalType();
                                }
                            Composition composition = clzSuper.getFormat() == Format.INTERFACE
                                    ? Composition.Implements : Composition.Extends;
                            setContribs.add(new Contribution(composition, typeSuper));
                            }
                        }
                    }
                }

            // we are finished walking up the parent chain AFTER we have processed the first class
            // in that chain that is NOT a virtual child class
            if (parent instanceof ClassStructure && !((ClassStructure) parent).isVirtualChild())
                {
                break;
                }

            parent = parent.getParent();
            ++cDepth;
            }

        return true;
        }

    @Override
    protected Object findVirtualChildSuper(
            IdentityConstant        idVirtChild,
            int                     cDepth,
            Set<IdentityConstant>   setVisited)
        {
        Object oResult = super.findVirtualChildSuper(idVirtChild, cDepth, setVisited);

        if (oResult == null && isVirtualChild())
            {
            // classes also walk up the parent chain (as long as the chain corresponds to a chain of
            // virtual children and the parent containing them) to find contributions that can lead
            // to the virtual child super
            oResult = getParent().findVirtualChildSuper(idVirtChild, cDepth+1, setVisited);
            }

        return oResult;
        }

    @Override
    protected Iterator<IdentityConstant> potentialVirtualChildContributors()
        {
        List<IdentityConstant> list = null;

        for (Contribution contrib : getContributionsAsList())
            {
            switch (contrib.getComposition())
                {
                case Extends:
                case Incorporates:
                case Annotation:
                case Implements:
                    TypeConstant type = contrib.getTypeConstant();
                    if (type.containsUnresolved())
                        {
                        return null;
                        }
                    if (type.isSingleUnderlyingClass(true))
                        {
                        if (list == null)
                            {
                            list = new ArrayList<>();
                            }

                        list.add(type.getSingleUnderlyingClass(true));
                        }
                }
            }

        return list == null
                ? Collections.emptyIterator()
                : list.iterator();
        }

    /**
     * Determine the "into" type of this mixin.
     *
     * @return a TypeConstant of the "into" contribution
     */
    public TypeConstant getTypeInto()
        {
        if (getFormat() != Format.MIXIN)
            {
            throw new IllegalStateException("not a mixin: " + getIdentityConstant());
            }

        Contribution contribInto = findContribution(Composition.Into);
        if (contribInto != null)
            {
            return contribInto.getTypeConstant();
            }

        ClassStructure structSuper = getSuper();
        if (structSuper != null)
            {
            return structSuper.getTypeInto();
            }

        return getConstantPool().typeObject();
        }

    /**
     * Find an index of a generic parameter with the specified name.
     *
     * Note: this method only looks for parameters declared by this class.
     *
     * @param sParamName  the parameter name
     *
     * @return the parameter index or -1 if not found
     */
    public int indexOfGenericParameter(String sParamName)
        {
        Iterator<Map.Entry<StringConstant, TypeConstant>> iterFormalEntry =
                getTypeParams().entrySet().iterator();
        for (int i = 0; iterFormalEntry.hasNext(); i++)
            {
            Map.Entry<StringConstant, TypeConstant> entry = iterFormalEntry.next();

            if (entry.getKey().getValue().equals(sParamName))
                {
                return i;
                }
            }
        return -1;
        }

    /**
     * Recursively check if the formal name is introduced by this class of any of its contributions.
     * <p/>
     * Note: while this seems to be a duplication of what TypoInfo does, we need to keep this
     * functionality since the TypeInfo generation itself uses it.
     *
     * @return true if the formal type with this name exists
     */
    public boolean containsGenericParamType(String sName)
        {
        return containsGenericParamTypeImpl(sName, true);
        }

    /**
     * Recursive implementation of containsGenericParamType method.
     *
     * @param sName       teh formal type name
     * @param fAllowInto  specifies whether or not the "Into" contribution is to be skipped
     *
     * @return the corresponding actual type or null if there is no matching formal type
     */
    protected boolean containsGenericParamTypeImpl(String sName, boolean fAllowInto)
        {
        int ix = indexOfGenericParameter(sName);
        if (ix >= 0)
            {
            return true;
            }

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();

            if (typeContrib.containsUnresolved() || !typeContrib.isSingleUnderlyingClass(true))
                {
                // TODO: how do we process relational types?
                continue;
                }

            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        break;
                        }
                case Annotation:
                case Delegates:
                case Implements:
                case Incorporates:
                case Extends:
                    ClassStructure clzContrib = (ClassStructure)
                            typeContrib.getSingleUnderlyingClass(true).getComponent();
                    if (clzContrib.containsGenericParamTypeImpl(sName, false))
                        {
                        return true;
                        }
                    break;

                default:
                    throw new IllegalStateException();
                }
            }

        return false;
        }

    /**
     * Recursively find the type for the specified formal name. Note that the formal name could
     * be introduced by some contributions, rather than this class itself.
     * <p/>
     * Note: while this seems to be a duplication of what TypoInfo does, we need to keep this
     * functionality since the TypeInfo generation itself uses it.
     *
     * @return the corresponding actual type or null if there is no matching formal type
     */
    public TypeConstant getGenericParamType(ConstantPool pool, String sName,
                                            List<TypeConstant> listActual)
        {
        return getGenericParamTypeImpl(pool, sName, listActual, true);
        }

    /**
     * Recursive implementation of getGenericParamType method.
     *
     * @param pool        the ConstantPool to place a potentially created new constant into
     * @param fAllowInto  specifies whether or not the "Into" contribution is to be skipped
     *
     * @return the corresponding actual type or null if there is no matching formal type
     */
    protected TypeConstant getGenericParamTypeImpl(ConstantPool pool, String sName,
                                                   List<TypeConstant> listActual, boolean fAllowInto)
        {
        TypeConstant type = extractGenericType(pool, sName, listActual);
        if (type != null)
            {
            return type;
            }

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();

            if (typeContrib.containsUnresolved() || !typeContrib.isSingleUnderlyingClass(true))
                {
                // TODO: how do we process relational types?
                continue;
                }

            TypeConstant typeResolved = contrib.resolveType(pool, this, listActual);
            if (typeResolved == null)
                {
                // conditional incorporation
                continue;
                }

            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        break;
                        }
                case Annotation:
                case Delegates:
                case Implements:
                case Incorporates:
                case Extends:
                    ClassStructure clzContrib = (ClassStructure)
                            typeContrib.getSingleUnderlyingClass(true).getComponent();
                    type = clzContrib.getGenericParamTypeImpl(pool, sName, typeResolved.getParamTypes(), false);
                    if (type != null)
                        {
                        return type;
                        }
                    break;

                default:
                    throw new IllegalStateException();
                }
            }

        return null;
        }

    /**
     * Extract a generic type for the formal parameter of the specified name from the specified list.
     *
     * @param pool   the ConstantPool to use
     * @param sName  the formal name
     * @param list   the actual type list
     *
     * @return the type corresponding to the specified formal type or null if cannot be determined
     */
    protected TypeConstant extractGenericType(ConstantPool pool, String sName,
                                              List<TypeConstant> list)
        {
        int ix = indexOfGenericParameter(sName);
        if (ix < 0)
            {
            return null;
            }

        if (isTuple())
            {
            return pool.ensureParameterizedTypeConstant(pool.typeTuple(),
                list.toArray(TypeConstant.NO_TYPES));
            }

        return ix < list.size() ? list.get(ix) : null;
        }


    // ----- type comparison support ---------------------------------------------------------------

    /**
     * Calculate assignability between two parameterized types of this class.
     *
     *  C<L1, L2, ...> lvalue = (C<R1, R2, ...>) rvalue;
     */
    public Relation calculateAssignability(List<TypeConstant> listLeft, Access accessLeft,
                                           List<TypeConstant> listRight)
        {
        ConstantPool pool = ConstantPool.getCurrentPool();

        int cParamsLeft  = listLeft.size();
        int cParamsRight = listRight.size();
        boolean fTuple   = isTuple();

        List<Map.Entry<StringConstant, TypeConstant>> listFormalEntries = getTypeParamsAsList();

        if (!fTuple)
            {
            if (Math.max(cParamsRight, cParamsLeft) > listFormalEntries.size())
                {
                // soft assert
                System.err.println("Invalid number of arguments for " + getName()
                        + ": required=" + listFormalEntries.size()
                        + ", provided " + Math.max(cParamsRight, cParamsLeft));
                return Relation.INCOMPATIBLE;
                }
            }

        boolean fWeak = false;
        for (int i = 0; i < Math.max(cParamsLeft, cParamsRight); i++)
            {
            String       sName;
            TypeConstant typeCanonical;

            if (fTuple)
                {
                sName         = null;
                typeCanonical = pool.typeObject();
                }
            else
                {
                Map.Entry<StringConstant, TypeConstant> entryFormal = listFormalEntries.get(i);

                sName         = entryFormal.getKey().getValue();
                typeCanonical = entryFormal.getValue();
                }

            if (i >= cParamsLeft)
                {
                // if an assignment C<L1> = C<R1> is allowed, then an assignment
                // C<L1> = C<R1, R2> is allowed for any R2, but it could be "weak"
                if (fTuple ||
                    (producesFormalType(sName, accessLeft, listLeft) &&
                     consumesFormalType(sName, accessLeft, listLeft)))
                    {
                    fWeak = true;
                    }
                break;
                }

            TypeConstant typeLeft = listLeft.get(i);
            TypeConstant typeRight;
            boolean fProduce;
            boolean fLeftIsRight = false;

            if (i < cParamsRight)
                {
                typeRight = listRight.get(i);

                if (typeLeft.equals(typeRight))
                    {
                    continue;
                    }

                fProduce = fTuple || producesFormalType(sName, accessLeft, listLeft);
                fLeftIsRight = typeLeft.isA(typeRight);

                if (fLeftIsRight && !fProduce)
                    {
                    // consumer only methods; rule 1.2.1
                    continue;
                    }
                }
            else
                {
                // REVIEW: shouldn't we simply ALWAYS disallow C<L1, L2> = C<R1>?
                // assignment  C<L1, L2> = C<R1> is not the same as
                //             C<L1, L2> = C<R1, [canonical type for R2]>;
                // the former is only allowed if class C produces L2
                // and then all L2 consuming methods (if any) must be "wrapped"
                typeRight = typeCanonical;
                fProduce  = fTuple || producesFormalType(sName, accessLeft, listLeft);
                }

            if (typeRight.isA(typeLeft))
                {
                if (fLeftIsRight)
                    {
                    // both hold true:
                    //   typeLeft.isA(typeRight), and
                    //   typeRight.isA(typeLeft)
                    // we take it that the types are congruent
                    // (e,g. "this:class", but with different declaration levels)
                    continue;
                    }

                if (fProduce)
                    {
                    // there are some producing methods; rule 1.2.2.2
                    // consuming methods may need to be "wrapped"
                    if (fTuple || consumesFormalType(sName, accessLeft, listLeft))
                        {
                        fWeak = true;
                        }
                    continue;
                    }
                }

            // this parameter didn't match
            return Relation.INCOMPATIBLE;
            }
        return fWeak ? Relation.IS_A_WEAK : Relation.IS_A;
        }

    /**
     * Helper method to find a method by the name and number of arguments.
     *
     * @param sName  the method name to find
     * @param cArgs  the number of arguments
     * @param aType  (optional or partial) an array of parameter types to match
     *
     * @return the specified MethodStructure of null if not found
     */
    public MethodStructure findMethod(String sName, int cArgs, TypeConstant... aType)
        {
        return findMethod(sName, method ->
            {
            if (method.getParamCount() != cArgs)
                {
                return false;
                }

            if (aType == null)
                {
                return true;
                }

            for (int i = 0, c = Math.min(method.getParamCount(), aType.length); i < c; i++)
                {
                TypeConstant typeParam = method.getParam(i).getType();
                TypeConstant typeTest  = aType[i];
                if (typeTest != null && !typeParam.equals(typeTest))
                    {
                    return false;
                    }
                }
            return true;
            });
        }

    /**
     * Helper method to find a method by the name and a predicate.
     *
     * @param sName  the method name to find
     * @param test   the predicate to check whether a method is a one to return
     *
     * @return the specified MethodStructure or null if not found
     */
    public MethodStructure findMethod(String sName, Predicate<MethodStructure> test)
        {
        MultiMethodStructure structMM = (MultiMethodStructure) getChild(sName);
        if (structMM != null)
            {
            for (MethodStructure method : structMM.methods())
                {
                if (test.test(method))
                    {
                    return method;
                    }
                }
            }

        return null;
        }

    /**
     * Find the specified constructor of this class.
     *
     * @param types  the types of the constructor parameters
     *
     * @return the constructor; never null
     *
     * @throws IllegalStateException if the constructor cannot be found
     */
    public MethodStructure findConstructor(TypeConstant... types)
        {
        MultiMethodStructure structMM = (MultiMethodStructure) getChild("construct");
        if (structMM == null)
            {
            throw new IllegalStateException("no constructors on " + this);
            }

        int cParams = types.length;
        NextMethod: for (MethodStructure structMethod : structMM.methods())
            {
            if (structMethod.getParamCount() == cParams)
                {
                for (int i = 0; i < cParams; ++i)
                    {
                    if (!structMethod.getParam(i).getType().equals(types[i]))
                        {
                        continue NextMethod;
                        }
                    }

                return structMethod;
                }
            }

        throw new IllegalStateException("no such constructor for " + cParams + " params on " + this);
        }

    /**
     * Helper method to find a method in this structure or the direct inheritance chain.
     *
     * @param sName        the method name
     * @param atypeParam   the parameter types (optional)
     * @param atypeReturn  the return types (optional)
     *
     * @return the first method that matches the specified types of null
     */
    public MethodStructure findMethod(String sName, TypeConstant[] atypeParam, TypeConstant[] atypeReturn)
        {
        ClassStructure struct = this;
        do
            {
            MultiMethodStructure mms = (MultiMethodStructure) struct.getChild(sName);
            if (mms != null)
                {
                nextMethod:
                for (MethodStructure method : mms.methods())
                    {
                    MethodConstant constMethod = method.getIdentityConstant();

                    TypeConstant[] atypeParamTest  = constMethod.getRawParams();
                    TypeConstant[] atypeReturnTest = constMethod.getRawReturns();

                    if (atypeParam != null)
                        {
                        int cParams = atypeParamTest.length;
                        if (cParams != atypeParam.length)
                            {
                            continue;
                            }

                        for (int i = 0; i < cParams; i++)
                            {
                            if (!atypeParamTest[i].isA(atypeParam[i]))
                                {
                                continue nextMethod;
                                }
                            }
                        }
                    if (atypeReturn != null)
                        {
                        int cReturns = atypeReturnTest.length;
                        if (cReturns != atypeReturn.length)
                            {
                            continue;
                            }

                        for (int i = 0; i < cReturns; i++)
                            {
                            if (!atypeReturnTest[i].isA(atypeReturn[i]))
                                {
                                continue nextMethod;
                                }
                            }
                        }
                    return method;
                    }
                }
            struct = struct.getSuper();
            }
        while (struct != null);

        return null;
        }

    /**
     * For this class structure representing an R-Value, find a contribution assignable to the
     * specified L-Value type.
     *
     * @param typeLeft    the L-Value type
     * @param typeRight   the R-Value type that this ClassStructure is for
     * @param fAllowInto  specifies whether or not the "Into" contribution is to be skipped
     *
     * @return the relation
     */
    public Relation findContribution(TypeConstant typeLeft,
                                     TypeConstant typeRight, boolean fAllowInto)
        {
        assert typeLeft.isSingleDefiningConstant();

        ConstantPool     pool        = ConstantPool.getCurrentPool();
        Constant         constIdLeft = typeLeft.getDefiningConstant();
        IdentityConstant idClzRight  = getIdentityConstant();

        switch (constIdLeft.getFormat())
            {
            case Module:
            case Package:
                // modules and packages are never parameterized
                return constIdLeft.equals(idClzRight) ? Relation.IS_A : Relation.INCOMPATIBLE;

            case NativeClass:
                constIdLeft = ((NativeRebaseConstant) constIdLeft).getClassConstant();
                // fall through
            case Class:
                if (constIdLeft.equals(pool.clzObject()))
                    {
                    return Relation.IS_A;
                    }

                if (constIdLeft.equals(idClzRight))
                    {
                    Relation relation = calculateAssignability(
                        typeLeft.getParamTypes(), typeLeft.getAccess(), typeRight.getParamTypes());
                    if (relation == Relation.INCOMPATIBLE || !isVirtualChild())
                        {
                        return relation;
                        }

                    assert typeLeft.isVirtualChild() && typeRight.isVirtualChild();

                    // for virtual child we need to repeat the check for the parent types
                    TypeConstant typeParentLeft  = typeLeft.getParentType();
                    TypeConstant typeParentRight = typeRight.getParentType();
                    Relation     relationParent  = getVirtualParent().
                            findContribution(typeParentLeft, typeParentRight, fAllowInto);
                    return relation.worseOf(relationParent);
                    }
                break;

            case Property:
            case TypeParameter:
            case FormalTypeChild:
                // r-value (this) is a real type; it cannot be assigned to a formal type
                return Relation.INCOMPATIBLE;

            case ThisClass:
            case ParentClass:
            case ChildClass:
                assert typeLeft.isAutoNarrowing(false);
                return findContribution(typeLeft.resolveAutoNarrowing(pool, false, null), typeRight, fAllowInto);

            default:
                throw new IllegalStateException("unexpected constant: " + constIdLeft);
            }

        TypeConstant typeRebase = getRebaseType();
        if (typeRebase != null)
            {
            ClassStructure clzRebase = (ClassStructure)
                typeRebase.getSingleUnderlyingClass(true).getComponent();

            // rebase types are never parameterized and therefor cannot be "weak"
            if (clzRebase.findContribution(typeLeft, clzRebase.getCanonicalType(), fAllowInto) == Relation.IS_A)
                {
                return Relation.IS_A;
                }
            }

        Relation relation = Relation.INCOMPATIBLE;

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            switch (contrib.getComposition())
                {
                case Into:
                    if (!fAllowInto)
                        {
                        break;
                        }
                    // fall through
                case Annotation:
                case Delegates:
                case Implements:
                    typeContrib = typeContrib.resolveGenerics(pool, typeRight.normalizeParameters(pool));
                    if (typeContrib != null)
                        {
                        relation = relation.bestOf(typeContrib.calculateRelation(typeLeft));
                        if (relation == Relation.IS_A)
                            {
                            return Relation.IS_A;
                            }
                        }
                    break;

                case Incorporates:
                case Extends:
                    {
                    // the identity constant for those contribution is always a class
                    assert typeContrib.isSingleDefiningConstant();

                    ClassConstant constContrib = (ClassConstant)
                        typeContrib.getSingleUnderlyingClass(true);

                    if (constContrib.equals(pool.clzObject()))
                        {
                        // ignore trivial "extends Object" contribution
                        continue;
                        }

                    TypeConstant typeResolved = contrib.resolveType(pool, this, typeRight.getParamTypes());
                    if (typeResolved != null)
                        {
                        relation = relation.bestOf(((ClassStructure) constContrib.getComponent()).
                            findContribution(typeLeft, typeResolved, false));
                        if (relation == Relation.IS_A)
                            {
                            return Relation.IS_A;
                            }
                        }
                    break;
                    }

                default:
                    throw new IllegalStateException();
                }
            }

        return relation;
        }

    /**
     * For this class structure representing an R-Value, find a contribution assignable to the
     * specified L-Value intersection type.
     *
     * @param typeLeft    the L-Value intersection type
     * @param listRight   the list of actual generic parameters for this class
     *
     * @return the relation
     */
    public Relation findIntersectionContribution(IntersectionTypeConstant typeLeft,
                                                 List<TypeConstant> listRight)
        {
        ConstantPool pool = ConstantPool.getCurrentPool();

        Relation relation = Relation.INCOMPATIBLE;

        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            switch (contrib.getComposition())
                {
                case Extends:
                    {
                    // the identity constant for extension is always a class
                    assert typeContrib.isSingleDefiningConstant();

                    ClassConstant constContrib = (ClassConstant)
                        typeContrib.getSingleUnderlyingClass(true);

                    if (constContrib.equals(pool.clzObject()))
                        {
                        // ignore trivial "extends Object" contribution
                        continue;
                        }

                    TypeConstant typeResolved = contrib.resolveType(pool, this, listRight);

                    relation = relation.bestOf(((ClassStructure) constContrib.getComponent()).
                        findIntersectionContribution(typeLeft, typeResolved.getParamTypes()));
                    if (relation == Relation.IS_A)
                        {
                        return Relation.IS_A;
                        }
                    break;
                    }

                case Into:
                case Annotation:
                case Implements:
                    typeContrib = typeContrib.resolveGenerics(pool, new SimpleTypeResolver(listRight));
                    if (typeContrib != null)
                        {
                        if (typeContrib.equals(pool.typeObject()))
                            {
                            return Relation.INCOMPATIBLE;
                            }

                        relation = relation.bestOf(typeContrib.calculateRelation(typeLeft));
                        if (relation == Relation.IS_A)
                            {
                            return Relation.IS_A;
                            }
                        }
                    break;

                case Delegates:
                case Incorporates:
                    // delegation and incorporation cannot be of an intersection type
                    return Relation.INCOMPATIBLE;

                default:
                    throw new IllegalStateException();
                }
            }
        return relation;
        }

    /**
     * Determine if this template consumes a formal type with the specified name for the specified
     * access policy.
     */
    public boolean consumesFormalType(String sName, Access access, List<TypeConstant> listActual)
        {
        return consumesFormalTypeImpl(sName, access, listActual, true);
        }

    protected boolean consumesFormalTypeImpl(String sName, Access access,
                                             List<TypeConstant> listActual, boolean fAllowInto)
        {
        assert indexOfGenericParameter(sName) >= 0;

        ConstantPool pool = ConstantPool.getCurrentPool();
        for (Component child : children())
            {
            if (child instanceof MultiMethodStructure)
                {
                for (MethodStructure method : ((MultiMethodStructure) child).methods())
                    {
                    if (!method.isStatic() && method.isAccessible(access)
                            && method.consumesFormalType(sName))
                        {
                        return true;
                        }
                    }
                }
            else if (child instanceof PropertyStructure)
                {
                PropertyStructure property = (PropertyStructure) child;

                if (property.isGenericTypeParameter())
                    {
                    // generic types don't consume
                    continue;
                    }

                TypeConstant constType = property.getType();
                if (!listActual.isEmpty())
                    {
                    constType = constType.resolveGenerics(pool, new SimpleTypeResolver(listActual));
                    }

                // TODO: add correct access check when added to the structure
                // TODO: add @RO support

                MethodStructure methodGet = property.getGetter();
                if ((methodGet == null || methodGet.isAccessible(access))
                        && constType.consumesFormalType(sName, Access.PUBLIC))
                    {
                    return true;
                    }

                MethodStructure methodSet = property.getSetter();
                if ((methodSet == null || methodSet.isAccessible(access))
                        && constType.producesFormalType(sName, Access.PUBLIC))
                    {
                    return true;
                    }
                }
            }

        // check the contributions
    nextContribution:
        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            switch (contrib.getComposition())
                {
                case Delegates:
                case Implements:
                    break;

                case Into:
                    if (!fAllowInto)
                        {
                        continue nextContribution;
                        }
                case Annotation:
                case Incorporates:
                case Extends:
                    // the identity constant for those contribution is always a class
                    assert typeContrib.isSingleDefiningConstant();
                    break;

                default:
                    throw new IllegalStateException();
                }

            if (typeContrib.isSingleDefiningConstant())
                {
                TypeConstant typeResolved = contrib.resolveType(pool, this, listActual);
                if (typeResolved == null || !typeResolved.isParamsSpecified())
                    {
                    // conditional incorporation didn't apply to the actual type
                    // or the contribution is not parameterized
                    continue;
                    }

                ClassStructure clzContrib = (ClassStructure)
                    typeContrib.getSingleUnderlyingClass(true).getComponent();

                Map<StringConstant, TypeConstant> mapFormal = clzContrib.getTypeParams();
                List<TypeConstant> listContribParams = clzContrib.normalizeParameters(
                    pool, typeContrib.getParamTypes());
                List<TypeConstant> listContribActual = typeResolved.getParamTypes();

                Iterator<TypeConstant> iterParams = listContribParams.iterator();
                Iterator<StringConstant> iterNames = mapFormal.keySet().iterator();

                while (iterParams.hasNext())
                    {
                    TypeConstant constParam = iterParams.next();
                    String sFormal = iterNames.next().getValue();

                    if (constParam.producesFormalType(sName, access)
                            && clzContrib.consumesFormalTypeImpl(sFormal, access, listContribActual, false)
                        ||
                        constParam.consumesFormalType(sName, access)
                            && clzContrib.producesFormalTypeImpl(sFormal, access, listContribActual, false))
                        {
                        return true;
                        }
                    }
                }
            else
                {
                // the only contributions of relation type could be delegations and implementations
                // and since they cannot be conditional at any level, the actual types won't matter
                // for further recursion
                if (typeContrib.consumesFormalType(sName, access))
                    {
                    return true;
                    }
                }
            }

        return false;
        }

    /**
     * Determine if this template produces a formal type with the specified name for the
     * specified access policy.
     */
    public boolean producesFormalType(String sName, Access access, List<TypeConstant> listActual)
        {
        return producesFormalTypeImpl(sName, access, listActual, true);
        }

    protected boolean producesFormalTypeImpl(String sName, Access access,
                                             List<TypeConstant> listActual, boolean fAllowInto)
        {
        assert indexOfGenericParameter(sName) >= 0;

        ConstantPool pool = ConstantPool.getCurrentPool();
        for (Component child : children())
            {
            if (child instanceof MultiMethodStructure)
                {
                for (MethodStructure method : ((MultiMethodStructure) child).methods())
                    {
                    if (!method.isStatic() && method.isAccessible(access)
                            && method.producesFormalType(sName))
                        {
                        return true;
                        }
                    }
                }
            else if (child instanceof PropertyStructure)
                {
                PropertyStructure property = (PropertyStructure) child;

                if (property.isGenericTypeParameter())
                    {
                    // generic types don't produce
                    continue;
                    }

                TypeConstant constType = property.getType();
                if (!listActual.isEmpty())
                    {
                    constType = constType.resolveGenerics(pool, new SimpleTypeResolver(listActual));
                    }

                // TODO: add correct access check when added to the structure
                // TODO: add @RO support

                MethodStructure methodGet = property.getGetter();
                if ((methodGet == null || methodGet.isAccessible(access)
                        && constType.producesFormalType(sName, Access.PUBLIC)))
                    {
                    return true;
                    }

                MethodStructure methodSet = property.getSetter();
                if ((methodSet == null || methodSet.isAccessible(access))
                        && constType.consumesFormalType(sName, Access.PUBLIC))
                    {
                    return true;
                    }
                }
            }

        // check the contributions
    nextContribution:
        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            switch (contrib.getComposition())
                {
                case Delegates:
                case Implements:
                    break;

                case Into:
                    if (!fAllowInto)
                        {
                        continue nextContribution;
                        }
                case Annotation:
                case Incorporates:
                case Extends:
                    // the identity constant for those contribution is always a class
                    assert typeContrib.isSingleDefiningConstant();
                    break;

                default:
                    throw new IllegalStateException();
                }

            if (typeContrib.isSingleDefiningConstant())
                {
                TypeConstant typeResolved = contrib.resolveType(pool, this, listActual);
                if (typeResolved == null || !typeResolved.isParamsSpecified())
                    {
                    // conditional incorporation didn't apply to the actual type
                    // or the contribution is not parameterized
                    continue;
                    }

                ClassStructure clzContrib = (ClassStructure)
                    typeContrib.getSingleUnderlyingClass(true).getComponent();

                Map<StringConstant, TypeConstant> mapFormal = clzContrib.getTypeParams();
                List<TypeConstant> listContribParams = clzContrib.normalizeParameters(
                    pool, typeContrib.getParamTypes());
                List<TypeConstant> listContribActual = typeResolved.getParamTypes();

                Iterator<TypeConstant> iterParams = listContribParams.iterator();
                Iterator<StringConstant> iterNames = mapFormal.keySet().iterator();

                while (iterParams.hasNext())
                    {
                    TypeConstant constParam = iterParams.next();
                    String sFormal = iterNames.next().getValue();

                    if (constParam.producesFormalType(sName, access)
                            && clzContrib.producesFormalTypeImpl(sFormal, access, listContribActual, false)
                        ||
                        constParam.consumesFormalType(sName, access)
                            && clzContrib.consumesFormalTypeImpl(sFormal, access, listContribActual, false))
                        {
                        return true;
                        }
                    }
                }
            else
                {
                // the only contributions of relation type could be delegations and implementations
                // and since they cannot be conditional at any level, the actual types won't matter
                // for further recursion
                if (typeContrib.producesFormalType(sName, access))
                    {
                    return true;
                    }
                }
            }

        return false;
        }

    /**
     * For this class structure representing an L-Value interface, check recursively if all properties
     * and methods have a matching (substitutable) property or method on the specified R-value type.
     *
     * @param typeRight   the type to look for the matching methods
     * @param accessLeft  the access level to limit the check for
     * @param listLeft    the actual generic parameters for this interface
     *
     * @return a set of method/property signatures that don't have a match
     */
    public Set<SignatureConstant> isInterfaceAssignableFrom(TypeConstant typeRight, Access accessLeft,
                                                            List<TypeConstant> listLeft)
        {
        ConstantPool           pool     = ConstantPool.getCurrentPool();
        Set<SignatureConstant> setMiss  = new HashSet<>();
        GenericTypeResolver    resolver = null;

        for (Component child : children())
            {
            if (child instanceof PropertyStructure)
                {
                PropertyStructure prop = (PropertyStructure) child;

                if (prop.isGenericTypeParameter())
                    {
                    if (!typeRight.containsGenericParam(prop.getName()))
                        {
                        setMiss.add(prop.getIdentityConstant().getSignature());
                        }
                    }
                else
                    {
                    // TODO: should we check the "Var" access?
                    if (!prop.isRefAccessible(accessLeft))
                        {
                        continue;
                        }

                    SignatureConstant sig = prop.getIdentityConstant().getSignature();
                    if (!listLeft.isEmpty())
                        {
                        if (resolver == null)
                            {
                            resolver = new SimpleTypeResolver(listLeft);
                            }
                        sig = sig.resolveGenericTypes(pool, resolver);
                        }

                    if (!typeRight.containsSubstitutableMethod(sig,
                            Access.PUBLIC, false, Collections.EMPTY_LIST))
                        {
                        setMiss.add(sig);
                        }
                    }
                }
            else if (child instanceof MultiMethodStructure)
                {
                MultiMethodStructure mms = (MultiMethodStructure) child;
                for (MethodStructure method : mms.methods())
                    {
                    if (!method.isAccessible(accessLeft))
                        {
                        continue;
                        }

                    SignatureConstant sig = method.getIdentityConstant().getSignature();

                    if (method.isFunction())
                        {
                        if (!typeRight.containsSubstitutableMethod(sig,
                                Access.PUBLIC, true, Collections.EMPTY_LIST))
                            {
                            setMiss.add(sig);
                            }
                        }
                    else
                        {
                        sig = sig.resolveAutoNarrowing(pool, null);

                        if (!listLeft.isEmpty())
                            {
                            if (resolver == null)
                                {
                                resolver = new SimpleTypeResolver(listLeft);
                                }
                            sig = sig.resolveGenericTypes(pool, resolver);
                            }

                        if (!typeRight.containsSubstitutableMethod(sig,
                                Access.PUBLIC, false, Collections.EMPTY_LIST))
                            {
                            setMiss.add(sig);
                            }
                        }
                    }
                }
            }

        for (Contribution contrib : getContributionsAsList())
            {
            if (contrib.getComposition() == Composition.Extends)
                {
                TypeConstant typeResolved = contrib.resolveType(pool, this, listLeft);

                ClassStructure clzSuper = (ClassStructure)
                    typeResolved.getSingleUnderlyingClass(true).getComponent();

                assert (clzSuper.getFormat() == Component.Format.INTERFACE);

                setMiss.addAll(
                    clzSuper.isInterfaceAssignableFrom(typeRight, accessLeft, typeResolved.getParamTypes()));
                }
            }
        return setMiss;
        }

    /**
     * Check recursively if this class contains a matching (substitutable) method or property.
     *
     * @param signature   the signature to look for the match for (formal parameters resolved)
     * @param access      the access level to limit the check to
     * @param fFunction   if true, the signature represents a function
     * @param listParams  the actual generic parameters for this interface
     *
     * @return true iff there is a matching method or property
     */
    public boolean containsSubstitutableMethod(SignatureConstant signature, Access access,
                                               boolean fFunction, List<TypeConstant> listParams)
        {
        return containsSubstitutableMethodImpl(signature, access, fFunction,
                listParams, getIdentityConstant(), true);
        }

    protected boolean containsSubstitutableMethodImpl(SignatureConstant signature, Access access,
                                                      boolean fFunction, List<TypeConstant> listParams,
                                                      IdentityConstant idClass, boolean fAllowInto)
        {
        ConstantPool pool  = ConstantPool.getCurrentPool();
        Component    child = getChild(signature.getName());

        if (signature.isProperty())
            {
            if (child instanceof PropertyStructure)
                {
                PropertyStructure prop = (PropertyStructure) child;

                // TODO: should we check the "Var" access?
                if (prop.isRefAccessible(access) &&
                    prop.isSubstitutableFor(pool, signature, listParams))
                    {
                    return true;
                    }
                }
            }
        else
            {
            if (child instanceof MultiMethodStructure)
                {
                MultiMethodStructure mms = (MultiMethodStructure) child;

                GenericTypeResolver resolver = listParams.isEmpty() ? null :
                    new SimpleTypeResolver(listParams);

                for (MethodStructure method : mms.methods())
                    {
                    SignatureConstant sigMethod = method.getIdentityConstant().getSignature();
                    if (method.isAccessible(access) && method.isFunction() == fFunction)
                        {
                        if (fFunction)
                            {
                            // functions must match exactly
                            if (sigMethod.equals(signature))
                                {
                                return true;
                                }
                            }
                        else
                            {
                            sigMethod = sigMethod.resolveAutoNarrowing(pool, null)
                                                 .resolveGenericTypes(pool, resolver);
                            if (sigMethod.isSubstitutableFor(signature, idClass.getType()))
                                {
                                return true;
                                }
                            }
                        }
                    }
                }
            }

        // check the contributions
    nextContribution:
        for (Contribution contrib : getContributionsAsList())
            {
            TypeConstant typeContrib = contrib.getTypeConstant();
            switch (contrib.getComposition())
                {
                case Delegates:
                case Implements:
                    break;

                case Into:
                    if (!fAllowInto)
                        {
                        continue nextContribution;
                        }
                    break;

                case Annotation:
                case Incorporates:
                case Extends:
                    // the identity constant for those contribution is always a class
                    assert typeContrib.isSingleDefiningConstant();
                    break;

                default:
                    throw new IllegalStateException();
                }

            if (typeContrib.isSingleDefiningConstant())
                {
                TypeConstant typeResolved = contrib.resolveType(pool, this, listParams);
                if (typeResolved != null)
                    {
                    ClassStructure clzContrib = (ClassStructure)
                        typeResolved.getSingleUnderlyingClass(true).getComponent();

                    if (clzContrib.containsSubstitutableMethodImpl(signature, access, fFunction,
                            typeResolved.getParamTypes(), idClass, false))
                        {
                        return true;
                        }
                    }
                }
            else
                {
                typeContrib = contrib.resolveGenerics(pool, new SimpleTypeResolver(listParams));

                if (typeContrib.containsSubstitutableMethod(signature, access, fFunction, new ArrayList<>()))
                    {
                    return true;
                    }
                }
            }
        return false;
        }


    // ----- run-time support ----------------------------------------------------------------------

    /**
     * Create a synthetic MethodStructure for a default initializer for a given type.
     * Note: the resulting method will be marked as abstract if there are no properties
     *       to initialize.
     *
     * @param typeStruct  the type, for which a default constructor is to be created
     * @param mapFields   the fields template
     *
     * @return the [synthetic] MethodStructure for the corresponding default constructor
     */
    public MethodStructure createInitializer(TypeConstant typeStruct, Map<Object, TypeComposition> mapFields)
        {
        ConstantPool pool   = ConstantPool.getCurrentPool();
        int          nFlags = Format.METHOD.ordinal() | Access.PUBLIC.FLAGS;

        // create an orphaned transient MethodStructure (using current pool)
        MethodConstant idMethod = pool.ensureMethodConstant(
                getIdentityConstant(), "default", TypeConstant.NO_TYPES, TypeConstant.NO_TYPES);

        MethodStructure method = new MethodStructure(this, nFlags, idMethod, null,
                Annotation.NO_ANNOTATIONS, Parameter.NO_PARAMS, Parameter.NO_PARAMS, true, false);

        MethodStructure.Code code = method.createCode();

        assert typeStruct.getAccess() == Access.STRUCT;

        TypeInfo infoType = typeStruct.ensureTypeInfo();
        for (Map.Entry<Object, TypeComposition> entry : mapFields.entrySet())
            {
            Object          nid      = entry.getKey();
            TypeComposition clzRef   = entry.getValue();
            PropertyInfo    infoProp = infoType.findPropertyByNid(nid);

            if (infoProp == null)
                {
                // synthetic field; skip
                continue;
                }

            PropertyConstant idField   = infoProp.getFieldIdentity();
            MethodConstant   idInit    = null;
            Constant         constInit = null;

            if (infoProp.isInitialized())
                {
                constInit = infoProp.getInitialValue();
                if (constInit == null)
                    {
                    idInit = infoProp.getInitializer();
                    }
                }
            else
                {
                if (!infoProp.isImplicitlyUnassigned())
                    {
                    constInit = infoProp.getType().getDefaultValue();
                    }
                }

            if (constInit != null)
                {
                code.add(new L_Set(idField, constInit));
                }
            else if (idInit != null)
                {
                MethodStructure methodInit = (MethodStructure) idInit.getComponent();
                if (methodInit.isFunction())
                    {
                    code.add(new Call_01(idInit, idField));
                    }
                else
                    {
                    code.add(new Invoke_01(new Register(typeStruct, Op.A_TARGET), idInit, idField));
                    }
                }

            if (clzRef != null)
                {
                // this is a ref; recurse
                MethodStructure methodInitRef = clzRef.ensureAutoInitializer();
                code.add(new Op()
                    {
                    @Override
                    public int process(Frame frame, int iPC)
                        {
                        GenericHandle hStruct = (GenericHandle) frame.getThis();
                        RefHandle     hRef    = (RefHandle) hStruct.getField(idField);

                        hRef.setField(GenericHandle.OUTER, hStruct);

                        return methodInitRef == null
                            ? iPC + 1
                            : frame.call1(methodInitRef, hRef.ensureAccess(Access.STRUCT),
                                    Utils.OBJECTS_NONE, A_IGNORE);
                        }

                    @Override
                    public void write(DataOutput out, ConstantRegistry registry)
                        {
                        }

                    @Override
                    public String toString()
                        {
                        return "initRef: " + idField;
                        }
                    });
                }
            }

        if (code.hasOps())
            {
            code.add(new Return_0());

            code.ensureAssembled();
            }
        else
            {
            method.setAbstract(true);
            }
        return method;
        }

    /**
     * Create a synthetic "delegating" method for this class that delegates to the specified
     * method on the specified property.
     *
     * @param method  the method to delegate to
     * @param sProp   the delegating property name
     *
     * @return a synthetic MethodStructure that has the auto-generated delegating code
     */
    public MethodStructure ensureDelegation(MethodStructure method, String sProp)
        {
        SignatureConstant sig            = method.getIdentityConstant().getSignature();
        MethodStructure   methodDelegate = findMethod(sig);
        if (methodDelegate == null)
            {
            TypeConstant typeFormal   = getFormalType();
            TypeConstant typePrivate  = getConstantPool().ensureAccessTypeConstant(typeFormal, Access.PRIVATE);
            TypeInfo     infoPrivate  = typePrivate.ensureTypeInfo();
            PropertyInfo infoProp     = infoPrivate.findProperty(sProp);
            TypeConstant typeProp     = infoProp.getType();

            Parameter[]  aParams      = method.getParamArray().clone();
            Parameter[]  aReturns     = method.getReturnArray().clone();

            methodDelegate  = createMethod(false, method.getAccess(), null,
                    aReturns, method.getName(), aParams, true, false);

            MethodStructure.Code code         = methodDelegate.createCode();
            MethodInfo           infoDelegate = typeProp.ensureTypeInfo().getMethodBySignature(sig);
            MethodConstant       idDelegate   = infoDelegate.getIdentity();
            int                  cParams      = method.getParamCount();
            int                  cReturns     = method.getReturnCount();
            Register[]           aregParam    = cParams  == 0 ? null : new Register[cParams];
            Register[]           aregReturn   = cReturns == 0 ? null : new Register[cReturns];

            for (int i = 0; i < cParams; i++)
                {
                aregParam[i] = new Register(aParams[i].getType(), i);
                }

            Register regProp = new Register(typeProp);

            code.add(new L_Get(infoProp.getIdentity(), regProp));

            switch (aReturns.length)
                {
                case 0:
                    switch (aParams.length)
                        {
                        case 0:
                            code.add(new Invoke_00(regProp, idDelegate));
                            break;

                        case 1:
                            code.add(new Invoke_10(regProp, idDelegate, aregParam[0]));
                            break;

                        default:
                            code.add(new Invoke_N0(regProp, idDelegate, aregParam));
                            break;
                        }
                    code.add(new Return_0());
                    break;

                case 1:
                    {
                    Register regReturn = new Register(aReturns[0].getType(), Op.A_STACK);
                    switch (aParams.length)
                        {
                        case 0:
                            code.add(new Invoke_01(regProp, idDelegate, regReturn));
                            break;

                        case 1:
                            code.add(new Invoke_11(regProp, idDelegate, aregParam[0], regReturn));
                            break;

                        default:
                            code.add(new Invoke_N1(regProp, idDelegate, aregParam, regReturn));
                            break;
                        }
                    code.add(new Return_1(regReturn));
                    break;
                    }

                default:
                    {
                    for (int i = 0; i < cReturns; i++)
                        {
                        aregReturn[i] = new Register(aReturns[i].getType());
                        }

                    switch (aParams.length)
                        {
                        case 0:
                            code.add(new Invoke_0N(regProp, idDelegate, aregReturn));
                            break;

                        case 1:
                            code.add(new Invoke_1N(regProp, idDelegate, aregParam[0], aregReturn));
                            break;

                        default:
                            code.add(new Invoke_NN(regProp, idDelegate, aregParam, aregReturn));
                            break;
                        }
                    code.add(new Return_N(aregReturn));
                    break;
                    }
                }

            code.ensureAssembled();
            }
        return methodDelegate;
        }

    /**
     * @return true iff a object of this class needs to hold a reference to its parent
     */
    public boolean isInstanceChild()
        {
        return isInnerClass() && !isStatic();
        }

    /**
     * Create necessary method structures for the Const interface functions and methods.
     *
     * All the methods that are created artificially will be marked as "transient" and should not be
     * persisted during the serialization phase.
     *
     * Note: we should not call "ensureAssembled" for generated code when called during the
     *       compilation phase; it will be done by the compiler.
     *
     * @param fDisassemble  if true, indicates that this method is called during the "disassemble"
     *                      phase, when the classes are constructed from it's persistent storage;
     *                      false indicates that it's called during the compilation, when classes
     *                      are created from the source code
     */
    public void synthesizeConstInterface(boolean fDisassemble)
        {
        assert getFormat() == Format.CONST;

        ConstantPool pool = getConstantPool();

        synthesizeConstFunction("equals",   2, pool.typeBoolean());
        synthesizeConstFunction("compare",  2, pool.typeOrdered());
        synthesizeConstFunction("hashCode", 1, pool.typeInt());
        synthesizeAppendTo(fDisassemble);
        }

    /**
     * Synthesize the function if it doesn't exist.
     */
    private void synthesizeConstFunction(String sName, int cParams, TypeConstant typeReturn)
        {
        MethodStructure fnThis = findMethod(sName, method ->
            {
            if (    method.getTypeParamCount() != 1
                 || method.getParamCount()     != 1  + cParams
                 || method.getReturnCount()    != 1
                 || !method.getReturnTypes()[0].equals(typeReturn))
                {
                return false;
                }

            for (int i = 1; i < cParams; i++)
                {
                TypeConstant typeParam = method.getParam(i).getType();
                if (!typeParam.isTypeParameter())
                    {
                    return false;
                    }
                }
            return true;
            });


        if (fnThis == null)
            {
            // 1) build parameters;
            //  note that to instantiate type parameters we need to have the method constant,
            //  which needs to be created UnresolvedTypeConstant first and resolved later
            ConstantPool pool     = getConstantPool();
            TypeConstant typeThis = pool.ensureThisTypeConstant(getIdentityConstant(), null);
            TypeConstant typeType = pool.ensureParameterizedTypeConstant(pool.typeType(), typeThis);

            Parameter[] aParam = new Parameter[1 + cParams];

            aParam[0] = new Parameter(pool, typeType, "CompileType", null, false, 0, true);

            if (cParams == 1)
                {
                aParam[1] = new Parameter(pool, new UnresolvedTypeConstant(pool,
                        new UnresolvedNameConstant(pool, new String[]{"CompileType"}, false)),
                        "value", null, false, 1, false);
                }
            else
                {
                for (int i = 1; i <= cParams; i++)
                    {
                    aParam[i] = new Parameter(pool, new UnresolvedTypeConstant(pool,
                            new UnresolvedNameConstant(pool, new String[]{"CompileType"}, false)),
                            "value" + i, null, false, i, false);
                    }
                }

            Parameter[] aReturn = new Parameter[]
                {
                new Parameter(pool, typeReturn, "", null, true, 0, false)
                };

            // 2) create the method structure and [yet unresolved] identity
            fnThis = createMethod(/*function*/ true, Constants.Access.PUBLIC, null,
                    aReturn, sName, aParam, /*hasCode*/ true, /*usesSuper*/ false);
            fnThis.markNative();

            // 3) resolve the identity
            MethodConstant idMethod    = fnThis.getIdentityConstant();
            TypeConstant[] atypeParams = idMethod.getRawParams();

            TypeParameterConstant constParam = pool.ensureRegisterConstant(idMethod, 0, "CompileType");
            TypeConstant          typeFormal = constParam.getType();

            for (int i = 1, c = atypeParams.length; i < c; i++)
                {
                ((UnresolvedTypeConstant) atypeParams[i]).resolve(typeFormal);
                }

            // 4) get rid of the unresolved constants
            fnThis.resolveTypedefs();
            }
        }

    /**
     * If explicit "toString()" exists and "appendTo()" does not, generate "appendTo()" to route to
     * "toString" and a trivial "estimateStringLength".
     */
    private void synthesizeAppendTo(boolean fEnsureAssembled)
        {
        MethodStructure methToString = findMethod("toString", 0);
        if (methToString != null)
            {
            ConstantPool pool         = getConstantPool();
            TypeConstant typeAppender = pool.ensureParameterizedTypeConstant(
                pool.ensureEcstasyTypeConstant("Appender"), pool.typeChar());

            MethodStructure methAppendTo = findMethod("appendTo", 1, typeAppender);
            if (methAppendTo == null)
                {
                Parameter[] aParam = new Parameter[]
                    {
                    new Parameter(pool, typeAppender, "appender", null, false, 0, false)
                    };
                Annotation[] aAnno = new Annotation[]
                    {
                    pool.ensureAnnotation(pool.clzOverride())
                    };

                methAppendTo = createMethod(/*function*/ false, Constants.Access.PUBLIC, aAnno,
                        Parameter.NO_PARAMS, "appendTo", aParam, /*hasCode*/ true, /*usesSuper*/ false);
                methAppendTo.markTransient();

                MethodStructure.Code code = methAppendTo.ensureCode();

                // void appendTo(Appender<Char> appender)
                //    {
                //    this.toString().appendTo(appender);
                //    }
                Register regThis     = new Register(typeAppender, Op.A_TARGET);
                Register regAppender = new Register(typeAppender, 0);
                Register regStack    = new Register(typeAppender, Op.A_STACK);

                code.add(new Invoke_01(regThis, methToString.getIdentityConstant(), regStack));
                code.add(new Invoke_10(regStack, methAppendTo.getIdentityConstant(), regAppender));
                code.add(new Return_0());

                if (fEnsureAssembled)
                    {
                    code.ensureAssembled();
                    }

                MethodStructure methEstimate = findMethod("estimateStringLength", 0);
                if (methEstimate == null)
                    {
                    Parameter[] aReturn = new Parameter[]
                        {
                        new Parameter(pool, pool.typeInt(), "", null, true, 0, false)
                        };
                    methEstimate = createMethod(/*function*/ false, Constants.Access.PUBLIC, aAnno,
                            aReturn, "estimateStringLength", Parameter.NO_PARAMS,
                            /*hasCode*/ true, /*usesSuper*/ false);
                    methEstimate.markTransient();

                    code = methEstimate.ensureCode();

                    // return 0;
                    code.add(new Return_1(pool.val0()));

                    if (fEnsureAssembled)
                        {
                        code.ensureAssembled();
                        }
                    }
                }
            }
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    protected void disassemble(DataInput in)
            throws IOException
        {
        super.disassemble(in);

        // read in the type parameters
        m_mapParams = disassembleTypeParams(in);
        }

    @Override
    protected void disassembleChildren(DataInput in, boolean fLazy) throws IOException
        {
        if (getFormat() == Format.CONST)
            {
            // load the children proactively and synthesize the funky interface
            super.disassembleChildren(in, /*lazy*/ false);

            synthesizeConstInterface(true);
            }
        else
            {
            super.disassembleChildren(in, fLazy);
            }
        }

    @Override
    protected void registerConstants(ConstantPool pool)
        {
        super.registerConstants(pool);

        // register the type parameters
        m_mapParams = registerTypeParams(m_mapParams);
        }

    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        super.assemble(out);

        // write out the type parameters
        assembleTypeParams(m_mapParams, out);
        }

    @Override
    public String getDescription()
        {
        StringBuilder sb = new StringBuilder();
        sb.append(super.getDescription())
          .append(", type-params=");

        final ListMap<StringConstant, TypeConstant> map = m_mapParams;
        if (map == null || map.size() == 0)
            {
            sb.append("none");
            }
        else
            {
            sb.append('<');
            boolean fFirst = true;
            for (Map.Entry<StringConstant, TypeConstant> entry : map.entrySet())
                {
                if (fFirst)
                    {
                    fFirst = false;
                    }
                else
                    {
                    sb.append(", ");
                    }

                sb.append(entry.getKey().getValue());

                TypeConstant constType = entry.getValue();

                // if constType is unresolved, we cannot call constType.isEcstasy("Object")
                if (!constType.getValueString().equals("Ecstasy:Object"))
                    {
                    sb.append(" extends ")
                      .append(constType);
                    }
                }
            sb.append('>');
            }

        return sb.toString();
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Assuming that this component is a class containing nested members, and using a value
     * previously returned from {@link IdentityConstant#getNestedIdentity()} that would be usable
     * in relation to this class, determine the nested component that the identity refers to.
     *
     * @param id  a nested identity, which is null, or a String name, or a SignatureConstant, or an
     *            instance of NestedIdentity
     *
     * @return the specified component, or null
     */
    public Component getNestedChild(Object id)
        {
        // a null identity indicates the class itself
        if (id == null)
            {
            return this;
            }

        // immediately-nested properties are identified by using only a string name
        if (id instanceof String)
            {
            return getChild((String) id);
            }

        // immediately-nested multi-method/method combinations are identified by using only a sig
        if (id instanceof SignatureConstant)
            {
            return findMethod((SignatureConstant) id);
            }

        return ((NestedIdentity) id).getIdentityConstant().relocateNestedIdentity(this);
        }


    // ----- Object methods ------------------------------------------------------------------------

    @Override
    public boolean equals(Object obj)
        {
        if (obj == this)
            {
            return true;
            }

        if (!(obj instanceof ClassStructure) || !super.equals(obj))
            {
            return false;
            }

        ClassStructure that = (ClassStructure) obj;

        // type parameters
        Map mapThisParams = this.m_mapParams;
        Map mapThatParams = that.m_mapParams;
        int cThisParams   = mapThisParams == null ? 0 : mapThisParams.size();
        int cThatParams   = mapThatParams == null ? 0 : mapThatParams.size();

        return cThisParams == cThatParams &&
            (cThisParams == 0 || mapThisParams.equals(mapThatParams));
        }


    // ----- inner class: SimpleTypeResolver -------------------------------------------------------

    /**
     * Generic type resolver based on the actual parameter list.
     */
    public class SimpleTypeResolver
            implements GenericTypeResolver
        {
        /**
         * Create a TypeResolver based on the actual type list.
         *
         * @param listActual  the actual type list
         */
        public SimpleTypeResolver(List<TypeConstant> listActual)
            {
            ConstantPool pool = getConstantPool();

            m_listActual = listActual;

            if (!getIdentityConstant().equals(pool.clzTuple()))
                {
                int cFormal = getTypeParamCount();
                int cActual = listActual.size();
                if (cFormal < cActual)
                    {
                    // soft assert; this should have already been reported
                    System.err.println("Too many parameters: " + listActual +
                                       " passed to " + ClassStructure.this);
                    }
                else if (cFormal > cActual)
                    {
                    m_listActual = listActual = new ArrayList<>(listActual); // clone
                    List<Map.Entry<StringConstant, TypeConstant>> entries = getTypeParamsAsList();

                    // fill the missing actual parameters with the canonical types
                    // Note: since there is a possibility of Tuple self-reference
                    // (e.g. Tuple<ElementTypes extends Tuple<ElementTypes...>>)
                    // we'll prime each args with Object for now
                    for (int i = cActual; i < cFormal; i++)
                        {
                        listActual.add(pool.typeObject());
                        }

                    for (int i = cActual; i < cFormal; i++)
                        {
                        // the canonical type itself could be formal, depending on another parameter
                        TypeConstant typeCanonical = entries.get(i).getValue();
                        listActual.set(i, typeCanonical.isFormalTypeSequence()
                                ? typeCanonical
                                : typeCanonical.resolveGenerics(pool, this));
                        }
                    }
                }
            }

        @Override
        public TypeConstant resolveGenericType(String sFormalName)
            {
            return extractGenericType(getConstantPool(), sFormalName, m_listActual);
            }

        private List<TypeConstant> m_listActual;
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * Recursion check for {@link #resolveContributedName}. Not thread-safe.
     */
    private boolean m_fVisited;

    /**
     * The name-to-type information for type parameters. The type constant is used to specify a
     * type constraint for the parameter.
     */
    private ListMap<StringConstant, TypeConstant> m_mapParams;

    /**
     * Cached formal type.
     */
    private transient TypeConstant m_typeFormal;

    /**
     * Cached canonical type.
     */
    private transient TypeConstant m_typeCanonical;
    }
