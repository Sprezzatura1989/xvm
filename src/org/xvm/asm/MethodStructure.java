package org.xvm.asm;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PrintWriter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import java.util.concurrent.CompletableFuture;

import org.xvm.asm.constants.ArrayConstant;
import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.SingletonConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.Op.ConstantRegistry;
import org.xvm.asm.Op.Prefix;

import org.xvm.asm.op.Nop;

import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHeap;
import org.xvm.runtime.ServiceContext;
import org.xvm.runtime.Utils;

import org.xvm.runtime.template.xModule;
import org.xvm.runtime.template.xNullable;

import org.xvm.util.ListMap;

import static org.xvm.util.Handy.indentLines;
import static org.xvm.util.Handy.readIndex;
import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.writePackedLong;


/**
 * An XVM Structure that represents a method or a function.
 */
public class MethodStructure
        extends Component
        implements GenericTypeResolver
    {
    // ----- constructors --------------------------------------------------------------------------

    /**
     * Construct a MethodStructure with the specified identity. This constructor is used to
     * deserialize a MethodStructure.
     *
     * @param xsParent   the XvmStructure (probably a FileStructure) that contains this structure
     * @param nFlags     the Component bit flags
     * @param constId    the constant that specifies the identity of the Module
     * @param condition  the optional condition for this ModuleStructure
     */
    protected MethodStructure(XvmStructure xsParent, int nFlags, MethodConstant constId,
                              ConditionalConstant condition)
        {
        super(xsParent, nFlags, constId, condition);
        }

    /**
     * Construct a method structure.
     *
     * @param xsParent     the XvmStructure (probably a FileStructure) that contains this structure
     * @param nFlags       the Component bit flags
     * @param constId      the constant that specifies the identity of the Module
     * @param condition    the optional condition for this ModuleStructure
     * @param annotations  an array of Annotations
     * @param aReturns     an array of Parameters representing the "out" values
     * @param aParams      an array of Parameters representing the "in" values
     * @param fHasCode     true indicates that the method has code
     * @param fUsesSuper   true indicates that the method is known to reference "super"
     */
    protected MethodStructure(XvmStructure xsParent, int nFlags, MethodConstant constId,
            ConditionalConstant condition, Annotation[] annotations,
            Parameter[] aReturns, Parameter[] aParams, boolean fHasCode, boolean fUsesSuper)
        {
        this(xsParent, nFlags, constId, condition);

        m_aAnnotations = annotations;
        m_aReturns     = aReturns;
        m_aParams      = aParams;

        if (aReturns.length > 0 && aReturns[0].isConditionalReturn())
            {
            setConditionalReturn(true);
            }

        int cTypeParams    = 0;
        int cDefaultParams = 0;
        for (Parameter param : aParams)
            {
            if (param.isTypeParameter())
                {
                ++cTypeParams;
                }
            else if (param.hasDefaultValue())
                {
                ++cDefaultParams;
                }
            else
                {
                // default parameters should (must) be trailing in order to be used as defaults
                cDefaultParams = 0;
                }
            }

        m_cTypeParams    = cTypeParams;
        m_cDefaultParams = cDefaultParams;
        m_FHasCode       = fHasCode;
        m_FUsesSuper     = fUsesSuper;
        }


    // ----- accessors -----------------------------------------------------------------------------

    /**
     * @return true iff this is a function, not a method
     */
    public boolean isFunction()
        {
        return isStatic() && !isConstructor();
        }

    /**
     * @return true iff this is a constructor, which is a specialized form of a function
     */
    public boolean isConstructor()
        {
        String  sName        = getName();
        boolean fConstructor = sName.equals("construct") || sName.equals("=");
        return fConstructor;
        }

    /**
     * @return true iff this is a constructor finalizer, which is a specialized form of a method
     */
    public boolean isConstructorFinalizer()
        {
        boolean fFinalizer = getName().equals("finally");
        assert !fFinalizer || !isFunction();
        return fFinalizer;
        }

    /**
     * @return the number of annotations
     */
    public int getAnnotationCount()
        {
        return m_aAnnotations == null ? 0 : m_aAnnotations.length;
        }

    /**
     * Get the Annotation structure that represents the i-th annotation.
     *
     * @param i  an index
     *
     * @return the i-th annotation
     */
    public Annotation getAnnotation(int i)
        {
        return i < 0 || i >= getAnnotationCount() ? null : m_aAnnotations[i];
        }

    /**
     * @return an array of Annotation structures that represent all annotations of the method, or
     *         null during assembly before the annotations have been split out from the return types
     */
    public Annotation[] getAnnotations()
        {
        return m_aAnnotations;
        }

    /**
     * Find an annotation with the specified annotation class.
     *
     * @param clzClass  the annotation class to search for
     *
     * @return the annotation of that annotation class, or null
     */
    public Annotation findAnnotation(ClassConstant clzClass)
        {
        for (Annotation annotation : m_aAnnotations)
            {
            if (annotation.getAnnotationClass().equals(clzClass))
                {
                return annotation;
                }
            }

        return null;
        }

    /**
     * @return true if the annotations have been resolved; false if this method has to be called
     *         later in order to resolve annotations
     */
    public boolean resolveAnnotations()
        {
        boolean fVoid = getReturnCount() == 0;
        int     cMove = 0;
        for (Annotation annotation : m_aAnnotations)
            {
            if (annotation.containsUnresolved())
                {
                return false;
                }

            TypeConstant typeInto = annotation.getAnnotationType().getExplicitClassInto();
            if (typeInto.containsUnresolved())
                {
                return false;
                }

            if (!fVoid && !typeInto.isIntoMethodType())
                {
                ++cMove;
                }
            }
        if (cMove == 0)
            {
            return true;
            }

        Annotation[] aAll  = m_aAnnotations;
        int          cAll  = aAll.length;
        if (cMove == cAll)
            {
            addReturnAnnotations(aAll);
            m_aAnnotations = Annotation.NO_ANNOTATIONS;
            return true;
            }

        int          cKeep = cAll - cMove;
        Annotation[] aKeep = new Annotation[cKeep];
        Annotation[] aMove = new Annotation[cMove];
        int          iKeep = 0;
        int          iMove = 0;
        for (Annotation annotation : m_aAnnotations)
            {
            if (annotation.getAnnotationType().getExplicitClassInto().isIntoMethodType())
                {
                aKeep[iKeep++] = annotation;
                }
            else
                {
                aMove[iMove++] = annotation;
                }
            }

        addReturnAnnotations(aMove);
        m_aAnnotations = aKeep;
        return true;
        }

    /**
     * @param annotations  the annotations to add to the return type (or the first non-conditional
     *                     return type, if there are multiple return types)
     */
    private void addReturnAnnotations(Annotation[] annotations)
        {
        assert m_aReturns != null;
        assert m_aReturns.length > 0;

        // determine which return value to modify (i.e. not the "conditional" value)
        int       iRet = 0;
        Parameter ret  = m_aReturns[iRet];
        if (ret.isConditionalReturn())
            {
            ret = m_aReturns[++iRet];
            }

        assert !ret.isConditionalReturn();
        assert iRet == ret.getIndex();

        ConstantPool pool = getConstantPool();
        TypeConstant type = annotations.length == 0
                ? ret.getType()
                : pool.ensureAnnotatedTypeConstant(ret.getType(), annotations);
        m_aReturns[iRet] = new Parameter(pool, type, ret.getName(), ret.getDefaultValue(), true, iRet, false);

        // build a new MethodConstant
        MethodConstant idOld = getIdentityConstant();
        MethodConstant idNew = pool.ensureMethodConstant(idOld.getParentConstant(), idOld.getName(), getParamTypes(), getReturnTypes());
        replaceThisIdentityConstant(idNew);
        }

    /**
     * @return true if all typedefs have been resolved; false if this method has to be called later
     *         in order to resolve typedefs
     */
    public boolean resolveTypedefs()
        {
        IdentityConstant idOld = getIdentityConstant();
        if (idOld.containsUnresolved())
            {
            return false;
            }

        IdentityConstant idNew = (IdentityConstant) idOld.resolveTypedefs();
        if (idNew != idOld)
            {
            replaceThisIdentityConstant(idNew);
            }
        return true;
        }

    /**
     * Fill in the parameters and returns for a lambda. (They are generally unknown when the method
     * is first created.)
     *
     * @param aParams   an array of parameter information
     * @param aReturns  an array of return type information
     */
    public void configureLambda(Parameter[] aParams, Parameter[] aReturns)
        {
        assert getIdentityConstant().isLambda() && getIdentityConstant().isNascent();

        m_aParams  = aParams;
        m_aReturns = aReturns;
        }

    /**
     * @return the number of return values
     */
    public int getReturnCount()
        {
        return m_aReturns.length;
        }

    /**
     * Get the Parameter structure that represents the i-th return value.
     *
     * @param i  an index
     *
     * @return the i-th return value
     */
    public Parameter getReturn(int i)
        {
        return m_aReturns[i];
        }

    /**
     * @return a list of Parameter structures that represent all return values of the method
     */
    public List<Parameter> getReturns()
        {
        return Arrays.asList(m_aReturns);
        }

    /**
     * @return an array of Parameter structures that represent all return values of the method
     */
    public Parameter[] getReturnArray()
        {
        return m_aReturns;
        }

    /**
     * @return an array of TypeConstant structures that represent the return types of the method
     */
    public TypeConstant[] getReturnTypes()
        {
        return toTypeArray(m_aReturns);
        }

    /**
     * @return the number of method parameters (including the number of type parameters)
     */
    public int getParamCount()
        {
        return m_aParams.length;
        }

    /**
     * @return the number of type parameters
     */
    public int getTypeParamCount()
        {
        return m_cTypeParams;
        }

    /**
     * @return the number of parameters with default values
     */
    public int getDefaultParamCount()
        {
        return m_cDefaultParams;
        }

    /**
     * Get the Parameter structure that represents the i-th method parameter. The type parameters
     * come first, followed by the ordinary parameters.
     *
     * @param i  an index
     *
     * @return the i-th method parameter
     */
    public Parameter getParam(int i)
        {
        return m_aParams[i];
        }

    /**
     * Get the Parameter structure that represents the parameter of the specified name.
     *
     * @param sName  a parameter name
     *
     * @return the parameter of the specified name or null if not found
     */
    public Parameter getParam(String sName)
        {
        Parameter[] aParam = m_aParams;
        for (int i= 0, c = aParam.length; i < c; i++)
            {
            Parameter param = aParam[i];
            if (param.getName().equals(sName))
                {
                return param;
                }
            }
        return null;
        }

    /**
     * @return true iff the specified parameter is a formal type parameter
     */
    public boolean isTypeParameter(int i)
        {
        return 0 <= i && i < m_cTypeParams;
        }

    /**
     * @return a list of Parameter structures that represent all parameters of the method
     */
    public List<Parameter> getParams()
        {
        return Arrays.asList(m_aParams);
        }

    /**
     * @return an array of Parameter structures that represent all parameters of the method
     */
    public Parameter[] getParamArray()
        {
        return m_aParams;
        }

    /**
     * @return an array of TypeConstant structures that represent the parameters to the method
     */
    public TypeConstant[] getParamTypes()
        {
        return toTypeArray(m_aParams);
        }

    /**
     * @return an array of types for the specified array of parameters
     */
    private static TypeConstant[] toTypeArray(Parameter[] aParam)
        {
        int            cParam = aParam.length;
        TypeConstant[] aType  = new TypeConstant[cParam];
        for (int i = 0; i < cParam; ++i)
            {
            aType[i] = aParam[i].getType();
            }
        return aType;
        }

    /**
     * Ensure that a Code object exists. If the MethodStructure was disassembled, then the Code will
     * hold the deserialized Ops. If the MethodStructure has been created but no Code has already
     * been created, then the Code will be empty. If the Code was already created, then that
     * previous Code will be returned.
     *
     * @return a Code object, or null iff this MethodStructure has been marked as native
     */
    public Code ensureCode()
        {
        if (isNative() || !hasCode())
            {
            return null;
            }

        Code code = m_code;
        if (code == null)
            {
            m_code = code = new Code(this);
            }
        return code;
        }

    /**
     * Create an empty Code object.
     *
     * @return a new and empty Code object
     */
    public Code createCode()
        {
        Code code;

        resetRuntimeInfo();

        m_fNative     = false;
        m_aconstLocal = null;
        m_abOps       = null;
        m_code = code = new Code(this);

        markModified();

        return code;
        }

    /**
     * @return the op-code array for this method
     */
    public Op[] getOps()
        {
        Code code = ensureCode();
        if (code == null)
            {
            throw new IllegalStateException("Method \"" +
                getIdentityConstant().getPathString() + "\" has not been compiled");
            }

        return code.getAssembledOps();
        }

    /**
     * @return the array of constants that are referenced by the code in this method
     */
    public Constant[] getLocalConstants()
        {
        return m_aconstLocal;
        }


    // ----- compiler support ----------------------------------------------------------------------

    /**
     * Given arrays of actual argument types and return types, return a ListMap with the actual
     * (resolved) type parameters types.
     * <p/>
     * For example: given a method:
     *      <T, U> T foo(U u, T t)
     * actual argument types:
     *      String, Int
     * and actual return type:
     *      Number
     * this method would return a map {"T":Number, "U":String}
     *
     * @param atypeArgs     the actual argument types
     * @param atypeReturns  (optional) the actual return types
     *
     * @return a ListMap of the resolved types in the natural order, keyed by the names;
     *         conflicting types will be not in the map
     */
    public ListMap<String, TypeConstant> resolveTypeParameters(TypeConstant[] atypeArgs,
                                                               TypeConstant[] atypeReturns)
        {
        int                           cTypeParams   = getTypeParamCount();
        ListMap<String, TypeConstant> mapTypeParams = new ListMap<>(cTypeParams);

        TypeConstant[] atypeMethodParams  = getParamTypes();
        TypeConstant[] atypeMethodReturns = getReturnTypes();
        int            cMethodParams      = atypeMethodParams.length - cTypeParams;
        int            cMethodReturns     = atypeMethodReturns.length;
        int            cArgs              = atypeArgs == null ? 0 : atypeArgs.length;
        int            cReturns           = atypeReturns == null ? 0 : atypeReturns.length;

        assert cArgs <= cMethodParams && cReturns <= cMethodReturns;

        NextParameter:
        for (int iT = 0; iT < cTypeParams; iT++)
            {
            Parameter param = getParam(iT);
            String    sName = param.getName(); // type parameter name (formal)

            for (int iA = 0; iA < cArgs; iA++)
                {
                TypeConstant typeFormal = atypeMethodParams[cTypeParams + iA];
                TypeConstant typeActual = atypeArgs[iA];

                if (typeActual != null)
                    {
                    TypeConstant typeResolved = typeFormal.resolveTypeParameter(typeActual, sName);
                    if (checkConflict(typeResolved, sName, mapTypeParams))
                        {
                        // different arguments cause the formal type to resolve into
                        // incompatible types
                        mapTypeParams.remove(sName);
                        continue NextParameter;
                        }
                    }
                }

            for (int iR = 0; iR < cReturns; iR++)
                {
                TypeConstant typeFormal = atypeMethodReturns[iR];
                TypeConstant typeActual = atypeReturns[iR];

                if (typeActual != null)
                    {
                    TypeConstant typeResolved = typeFormal.resolveTypeParameter(typeActual, sName);
                    if (checkConflict(typeResolved, sName, mapTypeParams))
                        {
                        // different arguments cause the formal type to resolve into
                        // incompatible types
                        mapTypeParams.remove(sName);
                        continue NextParameter;
                        }
                    }
                }

            if (!mapTypeParams.containsKey(sName))
                {
                // no extra knowledge; keep the formal parameter type
                mapTypeParams.put(sName, param.asTypeParameterType(getIdentityConstant()));
                }
            }
        return mapTypeParams;
        }

    /**
     * Put the resolved formal type in the specified map and ensure that there is no conflict.
     *
     * @return true iff there is a conflict
     */
    private static boolean checkConflict(TypeConstant typeResult, String sFormalName,
                                         Map<String, TypeConstant> mapTypeParams)
        {
        if (typeResult != null)
            {
            TypeConstant typePrev = mapTypeParams.get(sFormalName);
            if (typePrev != null)
                {
                typeResult = Op.selectCommonType(typePrev, typeResult, ErrorListener.BLACKHOLE);
                if (typeResult == null)
                    {
                    return true;
                    }
                }
            mapTypeParams.put(sFormalName, typeResult);
            }
        return false;
        }

    /**
     * Determine the number of steeps to get to the "outer this" from this method.
     *
     * @return the number of steps
     */
    public int getThisSteps()
        {
        int cSteps = 0;
        Component parent = getParent().getParent();
        while (!(parent instanceof ClassStructure))
            {
            if (parent instanceof PropertyStructure
                && ((PropertyStructure) parent).isRefAnnotated())
                {
                ++cSteps;
                }
            parent = parent.getParent();
            }
        return cSteps;
        }


    // ----- run-time support ----------------------------------------------------------------------

    /**
     * @return a scope containing just the parameters to the method
     */
    Scope createInitialScope()
        {
        Scope scope = new Scope();
        for (int i = 0, c = getParamCount(); i < c; ++i)
            {
            scope.allocVar();
            }
        return scope;
        }

    /**
     * Initialize the runtime information. This is done automatically.
     */
    public void ensureRuntimeInfo()
        {
        if (m_cScopes == 0)
            {
            Code code = ensureCode();
            if (code == null)
                {
                Scope scope = createInitialScope();
                m_cVars   = scope.getMaxVars();
                m_cScopes = scope.getMaxDepth();
                }
            else
                {
                code.ensureAssembled();
                assert m_cScopes > 0;
                }
            }
        }

    /**
     * Discard any runtime information.
     */
    public void resetRuntimeInfo()
        {
        m_code    = null;
        m_cVars   = 0;
        m_cScopes = 0;
        m_fNative = false;
        }

    /**
     * @return the number of variables (registers) necessary for a frame running this method's code
     *         (including the parameters)
     */
    public int getMaxVars()
        {
        ensureRuntimeInfo();
        return m_cVars;
        }

    /**
     * @return the number of scopes necessary for a frame running this method's code
     */
    public int getMaxScopes()
        {
        ensureRuntimeInfo();
        return m_cScopes;
        }

    /**
     * Specifies whether or not the method implementation is implemented at this virtual level.
     *
     * @param fAbstract  pass true to mark the method as abstract
     */
    public void setAbstract(boolean fAbstract)
        {
        if (fAbstract)
            {
            m_abOps   = null;
            m_code    = null;
            m_fNative = false;
            }

        super.setAbstract(fAbstract);
        }

    /**
     * @return true iff the method has been marked as native
     */
    public boolean isNative()
        {
        return m_fNative;
        }

    /**
     * Specifies that the method implementation is provided directly by the runtime, aka "native".
     */
    public void markNative()
        {
        setAbstract(false);
        resetRuntimeInfo();

        m_fNative    = true;
        m_fTransient = true;
        }

    /**
     * @return true iff the method has been marked as transient
     */
    public boolean isTransient()
        {
        return m_fTransient;
        }

    /**
     * Specifies that the method implementation is generated by the runtime, aka "transient".
     */
    public void markTransient()
        {
        m_fTransient = true;
        }

    /**
     * Determine if this method might act as a property initializer. For example, in the property
     * declaration:
     * <p/>
     * <code><pre>
     *     Int MB = KB * KB;
     * </pre></code>
     * <p/>
     * ... the value of the property could be compiled as an initializer function named "=":
     * <p/>
     * <code><pre>
     *     Int MB
     *       {
     *       Int "="()
     *         {
     *         return KB * KB;
     *         }
     *       }
     * </pre></code>
     *
     *
     * @return true iff this method is a public method (not function) named "get" that takes no
     *         parameters and returns a single value
     */
    public boolean isPotentialInitializer()
        {
        return getName().equals("=")
                && getReturnCount() == 1 && getParamCount() == 0
                && isConstructor() && !isConditionalReturn();
        }

    /**
     * Determine if this method is declared in a way that it could act as a property initializer for
     * the specified type.
     *
     * @param type      the RefType of the reference
     * @param resolver  an optional GenericTypeResolver that is used to resolve the property type
     *                  and the types in the signature if necessary
     *
     * @return true iff this method is a public method (not function) named "get" that takes no
     *         parameters and returns a single value of the specified type
     */
    public boolean isInitializer(TypeConstant type, GenericTypeResolver resolver)
        {
        ConstantPool pool = getConstantPool();
        return isPotentialInitializer() && (getReturn(0).getType().equals(type) || resolver != null &&
                getReturn(0).getType().resolveGenerics(pool, resolver).
                    equals(type.resolveGenerics(pool, resolver)));
        }

    /**
     * Determine if this method might act as a {@code Ref.get()}, aka a "getter".
     *
     * @return true iff this method is a public method (not function) named "get" that takes no
     *         parameters and returns a single value
     */
    public boolean isPotentialGetter()
        {
        return getName().equals("get")
                && getAccess() == Access.PUBLIC
                && getReturnCount() == 1 && getParamCount() == 0
                && !isFunction() && !isConditionalReturn();
        }

    /**
     * Determine if this method is declared in a way that it could act as a {@code Ref.get()} for
     * the specified type.
     *
     * @param type      the RefType of the reference
     * @param resolver  an optional GenericTypeResolver that is used to resolve the property type
     *                  and the types in the signature if necessary
     *
     * @return true iff this method is a public method (not function) named "get" that takes no
     *         parameters and returns a single value of the specified type
     */
    public boolean isGetter(TypeConstant type, GenericTypeResolver resolver)
        {
        ConstantPool pool = getConstantPool();
        return isPotentialGetter() && (getReturn(0).getType().equals(type) || resolver != null &&
                getReturn(0).getType().resolveGenerics(pool, resolver).
                    equals(type.resolveGenerics(pool, resolver)));
        }

    /**
     * Determine if this method might act as a {@code Ref.set()}, aka a "setter".
     *
     * @return true iff this method is a public method (not function) named "set" that takes one
     *         parameter and returns no value
     */
    public boolean isPotentialSetter()
        {
        return getName().equals("set")
                && getAccess() == Access.PUBLIC
                && getReturnCount() == 0 && getParamCount() == 1
                && !isFunction() && !isConditionalReturn();
        }

    /**
     * Determine if this method is declared in a way that it could act as a {@code Ref.set()} for
     * the specified type.
     *
     * @param type      the RefType of the reference
     * @param resolver  an optional GenericTypeResolver that is used to resolve the property type
     *                  and the types in the signature if necessary
     *
     * @return true iff this method is a public method (not function) named "set" that takes one
     *         parameter of the specified type and returns no value
     */
    public boolean isSetter(TypeConstant type, GenericTypeResolver resolver)
        {
        ConstantPool pool = getConstantPool();
        return isPotentialSetter() && (getParam(0).getType().equals(type) || resolver != null &&
                getParam(0).getType().resolveGenerics(pool, resolver).
                    equals(type.resolveGenerics(pool, resolver)));
        }

    /**
     * Obtain the corresponding "finally" method for this constructor.
     */
    public MethodStructure getConstructFinally()
        {
        assert isConstructor();

        MethodStructure structFinally = m_structFinally;
        if (structFinally == null)
            {
            if (m_idFinally == null)
                {
                return null;
                }
            m_structFinally = structFinally = (MethodStructure) m_idFinally.getComponent();
            }

        return structFinally;
        }

    /**
     * Set the corresponding "finally" method for this constructor.
     */
    public void setConstructFinally(MethodStructure structFinally)
        {
        assert isConstructor();

        m_structFinally = structFinally;
        m_idFinally     = structFinally.getIdentityConstant();
        }

    /**
     * @return true iff this method has code
     */
    public boolean hasCode()
        {
        if (m_FHasCode != null)
            {
            return m_FHasCode;
            }

        return m_code != null && m_code.hasOps();
        }

    /**
     * @return true iff this method contains a call to its super
     */
    public boolean usesSuper()
        {
        if (m_FUsesSuper != null)
            {
            return m_FUsesSuper;
            }

        Code code = ensureCode();
        if (code == null)
            {
            return false;
            }

        return m_FUsesSuper = code.usesSuper();
        }

    /**
     * Check if this method is accessible with the specified access policy.
     */
    public boolean isAccessible(Access access)
        {
        return getAccess().ordinal() <= access.ordinal();
        }

    /**
     * Determine if this method produces a formal type with the specified name.
     *
     * A method _m_ "produces" type _T_ if any of the following holds true:
     * 1. _m_ has a return type declared as _T_;
     * 2. _m_ has a return type that _"produces T"_;
     * 3. _m_ has a parameter type that _"consumes T"_.
     */
    public boolean producesFormalType(String sTypeName)
        {
        for (Parameter param : getParams())
            {
            if (param.getType().consumesFormalType(sTypeName, Access.PUBLIC))
                {
                return true;
                }
            }

        for (Parameter param : getReturns())
            {
            if (param.getType().producesFormalType(sTypeName, Access.PUBLIC))
                {
                return true;
                }
            }

        return false;
        }

    /**
     * Determine if this method consumes a formal type with the specified name.
     *
     * A method _m_ "consumes" type _T_ if any of the following holds true:
     * 1. _m_ has a parameter type declared as _T_;
     * 2. _m_ has a parameter type that _"produces T"_.
     * 3. _m_ has a return type that _"consumes T"_;
     */
    public boolean consumesFormalType(String sTypeName)
        {
        for (Parameter param : getParams())
            {
            if (param.getType().producesFormalType(sTypeName, Access.PUBLIC))
                {
                return true;
                }
            }

        for (Parameter param : getReturns())
            {
            if (param.getType().consumesFormalType(sTypeName, Access.PUBLIC))
                {
                return true;
                }
            }

        return false;
        }

    /**
     * Ensure that all SingletonConstants used by this method are initialized and the next frame is
     * ready to be called.
     *
     * @param frame      the caller's frame
     * @param frameNext  the frame that is about to execute this method; could be null
     *                   if the initialization is performed on the "main" container service
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int ensureInitialized(Frame frame, Frame frameNext)
        {
        if (m_fInitialized)
            {
            return frameNext == null
                    ? Op.R_NEXT
                    : frame.call(frameNext);
            }

        List<SingletonConstant> listSingletons = null;
        for (Constant constant : getLocalConstants())
            {
            listSingletons = addSingleton(constant, listSingletons);
            }

        if (listSingletons != null)
            {
            boolean fMainContext = false;

            for (SingletonConstant constSingleton : listSingletons)
                {
                ObjectHandle hValue = constSingleton.getHandle();
                if (hValue != null)
                    {
                    continue;
                    }

                ServiceContext ctxCurr = frame.f_context;
                if (!fMainContext)
                    {
                    ServiceContext ctxMain = ctxCurr.getMainContext();

                    if (ctxCurr == ctxMain)
                        {
                        fMainContext = true;
                        }
                    else
                        {
                        assert frameNext != null;

                        // we have at least one non-initialized singleton;
                        // call the main service to initialize them all
                        CompletableFuture<ObjectHandle> cfResult =
                            ctxMain.sendConstantRequest(frame, this);

                        // create a pseudo frame to deal with the wait
                        Frame frameWait = Utils.createWaitFrame(frame, cfResult, Op.A_IGNORE);
                        frameWait.addContinuation(frameCaller -> frameCaller.call(frameNext));

                        return frame.call(frameWait);
                        }
                    }

                // we are on the main context and can actually perform the initialization
                if (!constSingleton.markInitializing())
                    {
                    // this can only happen if we are called recursively
                    return frame.raiseException("Circular initialization");
                    }

                IdentityConstant constValue = constSingleton.getValue();

                ObjectHeap heap = ctxCurr.f_heapGlobal;
                int        iResult;
                switch (constValue.getFormat())
                    {
                    case Module:
                        iResult = xModule.INSTANCE.createConstHandle(frame, constValue);
                        break;

                    case Package:
                        throw new UnsupportedOperationException("not implemented"); // TODO

                    case Property:
                        iResult = callPropertyInitializer(frame, (PropertyConstant) constValue);
                        break;

                    case Class:
                        {
                        ClassConstant  idClz = (ClassConstant) constValue;
                        ClassStructure clz   = (ClassStructure) idClz.getComponent();

                        assert clz.isSingleton();

                        ClassTemplate template = heap.f_templates.getTemplate(idClz);

                        Format format = template.f_struct.getFormat();
                        if (format == Format.ENUMVALUE || format == Format.ENUM)
                            {
                            // this can happen if the constant's handle was not initialized or
                            // assigned on a different constant pool
                            iResult = template.createConstHandle(frame, constSingleton);
                            }
                        else
                            {
                            // the class must have a no-params constructor to call
                            MethodStructure constructor = clz.findMethod(getConstantPool().sigConstruct());
                            iResult = constructor == null
                                ? frame.raiseException(
                                    "Missing default constructor at " + clz.getSimpleName())
                                : template.construct(frame, constructor,
                                    template.getCanonicalClass(), null, Utils.OBJECTS_NONE, Op.A_STACK);
                            }
                        break;
                        }

                    default:
                        throw new IllegalStateException("unexpected defining constant: " + constValue);
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        constSingleton.setHandle(frame.popStack());
                        break; // next constant

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    case Op.R_CALL:
                        frame.m_frameNext.addContinuation(frameCaller ->
                            {
                            constSingleton.setHandle(frameCaller.popStack());
                            return ensureInitialized(frameCaller, frameNext);
                            });
                        return Op.R_CALL;

                    default:
                        throw new IllegalStateException();
                    }
                }
            }

        // all is done;
        // if on the main context, we are entitled to set the flag;
        // otherwise, we didn't do anything, so even if other threads don't immediately see the flag
        // (since it's not volatile) they will simply repeat the "do nothing" loop
        m_fInitialized = true;
        return frameNext == null
            ? frame.assignValue(0, xNullable.NULL) // the result is ignored, but has to be assigned
            : frame.call(frameNext);
        }

    /**
     * Add SingletonConstant(s) to the specified list.
     *
     * @param constant  the constant to check
     * @param list      the list to add to (could be null)
     *
     * @return the resulting list
     */
    private List<SingletonConstant> addSingleton(Constant constant, List<SingletonConstant> list)
        {
        if (constant instanceof SingletonConstant)
            {
            if (list == null)
                {
                list = new ArrayList<>(7);
                }
            list.add((SingletonConstant) constant);
            }
        else if (constant instanceof ArrayConstant)
            {
            for (Constant constElement : ((ArrayConstant) constant).getValue())
                {
                list = addSingleton(constElement, list);
                }
            }
        return list;
        }

    /**
     * Call the static property initializer.
     *
     * @param frame   the caller's frame
     * @param idProp  the property id
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    private int callPropertyInitializer(Frame frame, PropertyConstant idProp)
        {
        PropertyStructure prop   = (PropertyStructure) idProp.getComponent();
        ObjectHandle      hValue;

        assert prop.isStatic();

        Constant constVal = prop.getInitialValue();
        if (constVal == null)
            {
            // there must be an initializer
            MethodStructure methodInit = prop.getInitializer();
            ObjectHandle[]  ahVar      =
                Utils.ensureSize(Utils.OBJECTS_NONE, methodInit.getMaxVars());

            return frame.call1(methodInit, null, ahVar, Op.A_STACK);
            }
        hValue = frame.getConstHandle(constVal);
        frame.pushStack(hValue);
        return Op.R_NEXT;
        }

    /**
     * Find a parameter by name.
     *
     * @param sParam  the parameter name
     *
     * @return a parameter index; -1 if not found
     */
    public int findParameter(String sParam)
        {
        for (int i = 0, c = getParamCount(); i < c; i++)
            {
            Parameter param = getParam(i);
            if (sParam.equals(param.getName()))
                {
                return i;
                }
            }
        return -1;
        }

    /**
     * Calculate the line number for a given op counter.
     *
     * @return the corresponding line number (one-based) of zero if the line cannot be calculated
     */
    public int calculateLineNumber(int iPC)
        {
        Code code = m_code;
        if (code == null)
            {
            return 0;
            }

        Op[] aOp = code.m_aop;
        if (aOp == null)
            {
            return 0;
            }

        // Nop() line count is zero based
        int nLine = 1;
        for (int i = 0; i < iPC; i++)
            {
            Op op = aOp[i].ensureOp();
            if (op instanceof Nop)
                {
                nLine += ((Nop) op).getLineCount();
                }
            }
        return nLine == 1 ? 0 : nLine;
        }


    // ----- GenericTypeResolver interface ---------------------------------------------------------

    @Override
    public TypeConstant resolveGenericType(String sFormalName)
        {
        // look for a name match only amongst the method's formal type parameters
        for (int i = 0, c = getTypeParamCount(); i < c; i++)
            {
            Parameter param = getParam(i);

            if (sFormalName.equals(param.getName()))
                {
                TypeConstant typeType = param.getType();

                // type parameter's type must be of Type<DataType>
                assert typeType.isTypeOfType() && typeType.getParamsCount() == 1;
                return typeType.getParamTypesArray()[0];
                }
            }

        return null;
        }

    // ----- Component methods ---------------------------------------------------------------------

    @Override
    public boolean isConditionalReturn()
        {
        return super.isConditionalReturn();
        }

    @Override
    public void setConditionalReturn(boolean fConditional)
        {
        if (fConditional != isConditionalReturn())
            {
            // verify that the first return value is a boolean
            Parameter paramOld = m_aReturns[0];
            if (!(paramOld.getType().isEcstasy("Boolean")))
                {
                throw new IllegalStateException("first return value is not Boolean (" + paramOld + ")");
                }

            // change the first return value as specified
            m_aReturns[0] = new Parameter(getConstantPool(), paramOld.getType(), paramOld.getName(),
                    paramOld.getDefaultValue(), true, 0, fConditional);

            super.setConditionalReturn(fConditional);
            }
        }

    @Override
    public String getName()
        {
        return getIdentityConstant().getName();
        }

    @Override
    protected boolean isChildLessVisible()
        {
        return true;
        }

    @Override
    protected Component getEldestSibling()
        {
        MultiMethodStructure parent = (MultiMethodStructure) getParent();
        assert parent != null;

        Component sibling = parent.getMethodByConstantMap().get(getIdentityConstant());
        assert sibling != null;
        return sibling;
        }

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
        if (isFunction())
            {
            Component container = getParent().getParent();
            if (!(container instanceof ClassStructure))
                {
                return false;
                }

            // since funky interfaces allow auto-narrowing, we don't restrict the functions here;
            // however, we may need to limit this functionality later int the cycle, for example
            // during the function compilation (e.g. MethodDeclarationStatement##compile)
            }
        else if (isConstructor())
            {
            return false;
            }

        return getParent().isAutoNarrowingAllowed();
        }

    @Override
    public ResolutionResult resolveName(String sName, Access access, ResolutionCollector collector)
        {
        for (int i = 0, c = m_cTypeParams; i < c; ++i)
            {
            Parameter param = m_aParams[i];
            assert param.isTypeParameter();

            if (param.getName().equals(sName))
                {
                return collector.resolvedConstant(
                        getConstantPool().ensureRegisterConstant(getIdentityConstant(), i, sName));
                }
            }

        return super.resolveName(sName, access, collector);
        }

    @Override
    protected MethodStructure cloneBody()
        {
        MethodStructure that = (MethodStructure) super.cloneBody();

        // the m_aReturns array can have its contents altered by the annotation resolution logic
        if (this.m_aReturns != null && this.m_aReturns.length > 0)
            {
            that.m_aReturns = this.m_aReturns.clone();
            }

        // m_code is a mutable object, and tied back to the MethodStructure, so explicitly clone it
        if (this.m_code != null)
            {
            that.m_code = this.m_code.cloneOnto(that);
            }

        // note: ignoring the m_structFinally field

        return that;
        }


    // ----- XvmStructure methods ------------------------------------------------------------------

    @Override
    public MethodConstant getIdentityConstant()
        {
        return (MethodConstant) super.getIdentityConstant();
        }

    @Override
    protected void disassemble(DataInput in)
            throws IOException
        {
        super.disassemble(in);

        ConstantPool   pool              = getConstantPool();
        MethodConstant constMethod       = getIdentityConstant();
        TypeConstant[] aconstReturnTypes = constMethod.getRawReturns();
        TypeConstant[] aconstParamTypes  = constMethod.getRawParams();

        int          cAnnos = readMagnitude(in);
        Annotation[] aAnnos = cAnnos == 0 ? Annotation.NO_ANNOTATIONS : new Annotation[cAnnos];
        for (int i = 0; i < cAnnos; ++i)
            {
            aAnnos[i] = (Annotation) pool.getConstant(readMagnitude(in));
            }

        m_idFinally = (MethodConstant) pool.getConstant(readIndex(in));

        int         cReturns = aconstReturnTypes.length;
        Parameter[] aReturns = new Parameter[cReturns];
        boolean     fCond    = isConditionalReturn();
        for (int i = 0; i < cReturns; ++i)
            {
            Parameter param = new Parameter(pool, in, true, i, i==0 && fCond);
            if (!param.getType().equals(aconstReturnTypes[i]))
                {
                throw new IOException("type mismatch between method constant and return " + i + " value type");
                }
            aReturns[i] = param;
            }

        int         cTypeParams    = readMagnitude(in);
        int         cDefaultParams = readMagnitude(in);
        int         cParams        = aconstParamTypes.length;
        Parameter[] aParams        = new Parameter[cParams];
        for (int i = 0; i < cParams; ++i)
            {
            Parameter param = new Parameter(pool, in, false, i, i < cTypeParams);
            if (!param.getType().equals(aconstParamTypes[i]))
                {
                throw new IOException("type mismatch between method constant and param " + i + " value type");
                }
            aParams[i] = param;
            }

        // read local "constant pool"
        int        cConsts = readMagnitude(in);
        Constant[] aconst  = cConsts == 0 ? Constant.NO_CONSTS : new Constant[cConsts];
        for (int i = 0; i < cConsts; ++i)
            {
            aconst[i] = pool.getConstant(readMagnitude(in));
            }

        // read code
        byte[] abOps = null;
        int    cbOps = readMagnitude(in);
        if (cbOps > 0)
            {
            abOps = new byte[cbOps];
            in.readFully(abOps);
            }

        m_aAnnotations   = aAnnos;
        m_aReturns       = aReturns;
        m_cTypeParams    = cTypeParams;
        m_cDefaultParams = cDefaultParams;
        m_aParams        = aParams;
        m_aconstLocal    = aconst;
        m_abOps          = abOps;
        m_FHasCode       = abOps != null;
        }

    @Override
    protected void registerConstants(ConstantPool pool)
        {
        super.registerConstants(pool);

        for (int i = 0, c = m_aAnnotations.length; i < c; i++)
            {
            m_aAnnotations[i] = (Annotation) pool.register(m_aAnnotations[i]);
            }

        for (Parameter param : m_aReturns)
            {
            param.registerConstants(pool);
            }

        for (Parameter param : m_aParams)
            {
            param.registerConstants(pool);
            }

        // local constants:
        // (1) if code was created for this method, then it needs to register the constants;
        // (2) otherwise, if the local constants are present (because we read them in), then make
        //     sure they're all registered;
        // (3) otherwise, assume there are no local constants
        if (m_abOps != null)
            {
            // we didn't disassemble the individual ops, but we are responsible for registering the
            // constants that ops refer to
            Constant[] aconst = m_aconstLocal;
            if (aconst != null)
                {
                for (int i = 0, c = aconst.length; i < c; ++i)
                    {
                    aconst[i] = pool.register(aconst[i]);
                    }
                }
            }
        else if (m_code != null)
            {
            m_code.ensureConstantRegistry();
            }
        }

    @Override
    protected void assemble(DataOutput out)
            throws IOException
        {
        super.assemble(out);

        writePackedLong(out, m_aAnnotations.length);
        for (Annotation anno : m_aAnnotations)
            {
            writePackedLong(out, anno.getPosition());
            }

        writePackedLong(out, Constant.indexOf(m_idFinally));

        for (Parameter param : m_aReturns)
            {
            param.assemble(out);
            }

        writePackedLong(out, m_cTypeParams);
        writePackedLong(out, m_cDefaultParams);
        for (Parameter param : m_aParams)
            {
            param.assemble(out);
            }

        // produce the op bytes and "local constant pool"
        if (m_abOps == null && m_code != null)
            {
            try
                {
                m_code.ensureAssembled();
                }
            catch (UnsupportedOperationException e)
                {
                System.err.println("Error in MethodStructure.assemble() for "
                        + this.getParent().getContainingClass().getName() + "."
                        + this.getName() + ": " + e);
                }
            }

        // write out the "local constant pool"
        Constant[] aconst  = m_aconstLocal;
        int        cConsts = aconst == null ? 0 : aconst.length;
        writePackedLong(out, cConsts);
        for (int i = 0; i < cConsts; ++i)
            {
            writePackedLong(out, aconst[i].getPosition());
            }

        // write out the bytes (if there are any)
        byte[] abOps = m_abOps;
        int    cbOps = abOps == null ? 0 : abOps.length;
        writePackedLong(out, cbOps);
        if (cbOps > 0)
            {
            out.write(abOps);
            }
        }

    @Override
    public String getDescription()
        {
        MethodConstant id = getIdentityConstant();
        StringBuilder  sb = new StringBuilder();
        sb.append("host=\"")
          .append(getParent().getParent().getName())
          .append("\", id=\"")
          .append(id.getValueString());

        if (id.isLambda())
            {
            sb.append("\", lambda=")
              .append(id.getLambdaIndex());
            }

        sb.append("\", sig=")
          .append(id.isNascent() ? "n/a" : getIdentityConstant().getSignature());

        if (isNative())
            {
            sb.append(", native");
            }
        if (hasCode())
            {
            sb.append(", hasCode");
            }
        if (isConditionalReturn())
            {
            sb.append(", conditional");
            }

        sb.append(", type-param-count=")
          .append(m_cTypeParams)
          .append(", ")
          .append(super.getDescription());

        return sb.toString();
        }

    @Override
    protected void dump(PrintWriter out, String sIndent)
        {
        super.dump(out, sIndent);
        if (!isAbstract() && !isNative() && ensureCode().hasOps())
            {
            out.println(indentLines(ensureCode().toString(), nextIndent(sIndent)));
            }
        }

    /**
     * Collect the default arguments for the specified method.
     *
     * @param pool   the ConstantPool to use
     * @param cArgs  the number of explicitly specified arguments
     *               (must be no less than the number of non-default arguments)
     *
     * @return the array of constants or null if no defaults are needed
     */
    public Constant[] collectDefaultArgs(ConstantPool pool, int cArgs)
        {
        int cParamsAll  = getParamCount();
        int cTypeParams = getTypeParamCount();
        int cDefault    = cParamsAll - cTypeParams - cArgs;
        if (cDefault == 0)
            {
            return null;
            }

        Constant[] aconstDefault = new Constant[cDefault];
        for (int i = 0; i < cDefault; i++)
            {
            Parameter param = getParam(cTypeParams + cArgs + i);
            assert param.hasDefaultValue();

            aconstDefault[i] = param.getDefaultValue();
            }
        return aconstDefault;
        }

    /**
     * As part of the assembly process, register the default parameter values for this method.
     *
     * @param registry  the ConstantRegistry to use
     */
    protected void registerDefaultArgs(ConstantRegistry registry)
        {
        int cDefault = getDefaultParamCount();
        if (cDefault > 0)
            {
            int cAll = getParamCount();
            for (int i = cAll - cDefault; i < cAll; i++)
                {
                getParam(i).getDefaultValue().registerConstants(registry);
                }
            }
        }


    // ----- inner class: Code ---------------------------------------------------------------------

    /**
     * The Code class represents the op codes that make up a method's behavior.
     */
    public static class Code
        {
        // ----- constructors -----------------------------------------------------------------

        /**
         * Construct a Code object. This disassembles the bytes of code from the MethodStructure if
         * it was itself disassembled; otherwise this starts empty and allows ops to be added.
         */
        Code(MethodStructure method)
            {
            assert method != null;
            f_method = method;

            byte[] abOps = method.m_abOps;
            if (abOps != null)
                {
                Op[] aop;
                Constant[] aconst = method.getLocalConstants();
                try
                    {
                    aop = abOps.length == 0
                            ? Op.NO_OPS
                            : Op.readOps(new DataInputStream(new ByteArrayInputStream(abOps)), aconst);
                    }
                catch (IOException e)
                    {
                    throw new RuntimeException(e);
                    }
                m_aop = aop;

                // now that the ops have been read in, introduce them to the whole of the body of
                // code and the constants
                addressAndSimulateOps();
                for (int i = 0, c = aop.length; i < c; ++i)
                    {
                    // TODO ops that can jump need to implement this method and resolve their address to an op
                    //      (otherwise code read from disk will break when we eliminate dead & redundant code)
                    aop[i].resolveCode(this, aconst);
                    }
                }
            }

        Code(MethodStructure method, Code wrappee)
            {
            assert method != null;
            assert wrappee != null;

            f_method = method;
            }

        // ----- Code methods -----------------------------------------------------------------

        /**
         * Obtain the op at the specified index.
         * <p/>
         * This method is intended to support implementation of the {@link Op#resolveCode
         * Op.resolveCode()} method.
         *
         * @param i  the index (absolute address) of the Op to obtain
         *
         * @return the specified op
         */
        public Op get(int i)
            {
            return m_aop[i];
            }

        /**
         * Update the "current" line number from the corresponding source code.
         *
         * @param nLine  the new line number
         */
        public void updateLineNumber(int nLine)
            {
            m_nCurLine = nLine;
            }

        /**
         * Add the specified op to the end of the code.
         *
         * @param op  the Op to add
         *
         * @return this
         */
        public Code add(Op op)
            {
            ensureAppending();

            int nLineDelta = m_nCurLine - m_nPrevLine;
            if (nLineDelta != 0)
                {
                m_nPrevLine = m_nCurLine;
                add(new Nop(nLineDelta));
                }

            ArrayList<Op> listOps = m_listOps;
            if (m_fTrailingPrefix)
                {
                // get the last op and append this op to it
                ((Prefix) listOps.get(listOps.size()-1)).append(op);
                }
            else
                {
                listOps.add(op);
                }

            m_fTrailingPrefix = op instanceof Prefix;
            m_mapIndex        = null;

            return this;
            }

        /**
         * @return the register created by the last-added op
         */
        public Register lastRegister()
            {
            List<Op> list = m_listOps;
            if (!list.isEmpty())
                {
                Op  op = null;
                int of = 0;
                do
                    {
                    ++of;
                    op = list.get(list.size() - 1);
                    while (op instanceof Op.Prefix)
                        {
                        op = ((Op.Prefix) op).getNextOp();
                        }
                    }
                while (op == null);

                if (op instanceof OpVar)
                    {
                    return ((OpVar) op).getRegister();
                    }

                throw new IllegalStateException("op=" + op);
                }

            throw new IllegalStateException("no ops");
            }

        /**
         * @return true iff any of the op codes refer to the "super"
         */
        public boolean usesSuper()
            {
            Op[] aop = ensureOps();
            if (aop != null)
                {
                for (Op op : aop)
                    {
                    if (op.usesSuper())
                        {
                        return true;
                        }
                    }
                }
            return false;
            }

        /**
         * @return the array of Ops that make up the Code
         */
        public Op[] getAssembledOps()
            {
            ensureAssembled();
            return m_aop;
            }

        /**
         * @return true iff there are any ops in the code
         */
        public boolean hasOps()
            {
            return m_listOps != null && !m_listOps.isEmpty()
                || f_method.m_abOps != null && f_method.m_abOps.length > 0;
            }

        /**
         * Create a clone of this code that will exist on the specified method structure.
         *
         * @param method  the method structure to graft a clone onto
         *
         * @return the new Code clone
         */
        Code cloneOnto(MethodStructure method)
            {
            Code that = new Code(method, this);

            if (this.m_listOps != null)
                {
                // this isn't 100% correct, since a few ops are mutable in theory, but unless the
                // clone is made in the middle of code being added (which is not a supported time
                // at which to be calling clone), then this should be fine; (otherwise we'd have to
                // individually clone every single op)
                that.m_listOps = new ArrayList<>(this.m_listOps);
                }

            that.m_mapIndex        = this.m_mapIndex;
            that.m_fTrailingPrefix = this.m_fTrailingPrefix;
            that.m_aop             = this.m_aop;
            that.m_nPrevLine       = this.m_nPrevLine;
            that.m_nCurLine        = this.m_nCurLine;

            return that;
            }

        // ----- helpers for building Ops -----------------------------------------------------

        /**
         * @return the enclosing MethodStructure
         */
        public MethodStructure getMethodStructure()
            {
            return f_method;
            }

        /**
         * @return a blackhole if the code is not reachable
         */
        public Code onlyIf(boolean fReachable)
            {
            return fReachable ? this : blackhole();
            }

        /**
         * @return a Code instance that pretends to be this but ignores any attempt to add ops
         */
        public Code blackhole()
            {
            Code hole = m_hole;
            if (hole == null)
                {
                m_hole = hole = new BlackHole(this);
                }

            return hole;
            }

        // ----- Object methods ---------------------------------------------------------------

        @Override
        public String toString()
            {
            if (m_listOps == null && m_aop == null)
                {
                return "native";
                }

            Op[]          aOp = m_aop == null ? m_listOps.toArray(Op.NO_OPS) : m_aop;
            StringBuilder sb  = new StringBuilder();

            int i = 0;
            for (Op op : aOp)
                {
                sb.append("\n[")
                  .append(i++)
                  .append("] ")
                  .append(op.toString());
                }

            return sb.substring(1);
            }

        // ----- read-only wrapper ------------------------------------------------------------

        /**
         * An implementation of Code that delegates most functionality to a "real" Code object, but
         * silently ignores any attempt to actually change the code.
         */
        static class BlackHole
                extends Code
            {
            BlackHole(Code wrappee)
                {
                super(wrappee.f_method, wrappee);
                f_wrappee = wrappee;
                }

            @Override
            public Code add(Op op)
                {
                return this;
                }

            @Override
            public Register lastRegister()
                {
                return f_wrappee.lastRegister();
                }

            @Override
            public boolean usesSuper()
                {
                return false;
                }

            @Override
            public Op[] getAssembledOps()
                {
                throw new IllegalStateException();
                }

            @Override
            public Code blackhole()
                {
                return this;
                }

            @Override
            public String toString()
                {
                return "<blackhole>";
                }

            Code f_wrappee;
            }

        // ----- internal ---------------------------------------------------------------------

        protected void ensureAppending()
            {
            if (f_method.m_abOps != null)
                {
                throw new IllegalStateException("not appendable");
                }

            if (m_listOps == null)
                {
                m_listOps = new ArrayList<>();
                }
            }

        protected ConstantRegistry ensureConstantRegistry()
            {
            ConstantRegistry registry;
            if (f_method.m_abOps == null)
                {
                f_method.m_registry = registry = new ConstantRegistry(f_method.getConstantPool());

                f_method.registerDefaultArgs(registry);

                Op[] aop = ensureOps();
                for (Op op : aop)
                    {
                    op.registerConstants(registry);
                    }
                }
            else
                {
                registry = f_method.m_registry;
                assert registry != null;
                }

            return registry;
            }

        /**
         * Walk through all of the code paths, determining what code is reachable versus
         * unreachable, and eliminate the unreachable code.
         *
         * @return true iff any changes occurred
         */
        private boolean eliminateDeadCode()
            {
            // first, mark all the ops with their locations
            addressAndSimulateOps();

            // "color" the graph of reachable ops
            Op[] aop = ensureOps();
            follow(0);

            // scan through it sequentially, compacting to eliminate any unreachable ops
            int cOld = aop.length;
            int cNew = 0;
            for (int iOld = 0; iOld < cOld; ++iOld)
                {
                Op op = aop[iOld];
                if (!op.isDiscardable())
                    {
                    if (cNew < iOld)
                        {
                        aop[cNew] = op;
                        }
                    ++cNew;
                    }
                }

            if (cNew == cOld)
                {
                return false;
                }

            Op[] aopNew = new Op[cNew];
            System.arraycopy(aop, 0, aopNew, 0, cNew);
            m_aop = aopNew;
            return true;
            }

        private void follow(int iPC)
            {
            Op[] aop = m_aop;
            Op   op  = aop[iPC];
            if (op.isReachable())
                {
                return;
                }

            List<Integer> listBranches = new ArrayList<>();
            do
                {
                op.markReachable(aop);

                if (op.branches(aop, listBranches))
                    {
                    for (int cJmp : listBranches)
                        {
                        follow(iPC + cJmp);
                        }
                    listBranches.clear();
                    }

                if (!op.advances())
                    {
                    return;
                    }

                try
                    {
                    op = aop[++iPC];
                    }
                catch (ArrayIndexOutOfBoundsException e)
                    {
                    throw new IllegalStateException("illegal op-code: " + this);
                    }
                }
            while (!op.isReachable());
            }

        /**
         * Walk over all of the code, determining what ops are redundant (have no net effect), and
         * eliminate that redundant code.
         *
         * @return true iff any changes occurred
         */
        private boolean eliminateRedundantCode()
            {
            // first, mark all the ops with their locations, and determine which enter/exit pairs
            // are redundant
            addressAndSimulateOps();

            // next, scan for additional redundant ops
            Op[]    aop  = ensureOps();
            boolean fMod = false;
            for (Op op : aop)
                {
                fMod |= op.checkRedundant(aop);
                }

            if (!fMod)
                {
                return false;
                }

            // next, scan through sequentially, keeping the remaining non-redundant ops
            int    cOld     = aop.length;
            int    cNew     = 0;
            Prefix opPrefix = null;
            for (int iOld = 0; iOld < cOld; ++iOld)
                {
                Op op = aop[iOld];
                if (op.isRedundant())
                    {
                    if (opPrefix == null)
                        {
                        opPrefix = op.convertToPrefix();
                        }
                    else
                        {
                        opPrefix.append(op.convertToPrefix());
                        }
                    }
                else
                    {
                    if (cNew < iOld)
                        {
                        if (opPrefix == null)
                            {
                            aop[cNew] = op;
                            }
                        else
                            {
                            aop[cNew] = opPrefix.append(op);
                            opPrefix  = null;
                            }
                        }
                    ++cNew;
                    }
                }

            // there was redundant code; we should have seen code shrinkage
            assert cNew != cOld;

            Op[] aopNew = new Op[cNew];
            System.arraycopy(aop, 0, aopNew, 0, cNew);
            m_aop = aopNew;
            return true;
            }

        /**
         * Mark all the ops with their locations and scope depth.
         */
        private void addressAndSimulateOps()
            {
            Op[] aop = ensureOps();
            for (Op op : aop)
                {
                op.resetSimulation();
                }

            Scope scope = f_method.createInitialScope();
            for (int i = 0, c = aop.length; i < c; ++i)
                {
                Op op = aop[i];
                op.initInfo(i, scope.getCurDepth());
                op.simulate(scope);
                }

            for (Op op : aop)
                {
                op.resolveAddresses(aop);
                }

            f_method.m_cVars   = scope.getMaxVars();
            f_method.m_cScopes = scope.getMaxDepth();
            }

        protected void ensureAssembled()
            {
            if (f_method.m_abOps == null)
                {
                // it is possible that the elimination of dead code makes it possible to find new
                // redundant code, and vice versa
                do
                    {
                    eliminateDeadCode();
                    }
                while (eliminateRedundantCode());
                // note that the last call to eliminateRedundantCode() did not modify the code, so
                // each op will already have been stamped with the correct address and scope depth

                // populate the local constant registry
                ConstantRegistry registry = ensureConstantRegistry();

                // assemble the ops into bytes
                ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
                DataOutputStream      outData  = new DataOutputStream(outBytes);
                try
                    {
                    Op[] aOp = ensureOps();

                    writePackedLong(outData, aOp.length);
                    for (Op op : aOp)
                        {
                        op.write(outData, registry);
                        }
                    }
                catch (IOException e)
                    {
                    throw new IllegalStateException(e);
                    }

                f_method.m_abOps       = outBytes.toByteArray();
                f_method.m_aconstLocal = registry.getConstantArray();
                f_method.m_registry    = null;
                f_method.markModified();
                }
            }

        private Op[] ensureOps()
            {
            Op[] aop = m_aop;
            if (aop == null)
                {
                if (m_listOps == null)
                    {
                    throw new UnsupportedOperationException("Method \""
                            + f_method.getIdentityConstant().getPathString()
                            + "\" is neither native nor compiled");
                    }
                m_aop = aop = m_listOps.toArray(Op.NO_OPS);
                }
            return aop;
            }

        // ----- fields -----------------------------------------------------------------------

        /**
         * The containing method.
         */
        protected final MethodStructure f_method;

        /**
         * List of ops being assembled.
         */
        private ArrayList<Op> m_listOps;

        /**
         * Lookup of op address by op.
         */
        private IdentityHashMap<Op, Integer> m_mapIndex;

        /**
         * True iff the last op added was a prefix.
         */
        private boolean m_fTrailingPrefix;

        /**
         * The array of ops.
         */
        private Op[] m_aop;

        /**
         * A coding black hole.
         */
        private Code m_hole;

        /**
         * Previously advanced-to line number.
         */
        private int m_nPrevLine;

        /**
         * Current line number.
         */
        private int m_nCurLine;
        }


    // ----- fields --------------------------------------------------------------------------------

    /**
     * The method annotations.
     */
    private Annotation[] m_aAnnotations;

    /**
     * For constructors, an optional identity of the corresponding "finally" block.
     */
    private MethodConstant m_idFinally;

    /**
     * The return value types. (A zero-length array is "void".)
     */
    private Parameter[] m_aReturns;

    /**
     * The number of type parameters.
     */
    private int m_cTypeParams;

    /**
     * The number of parameters with default values.
     */
    private int m_cDefaultParams;

    /**
     * The parameter types.
     */
    private Parameter[] m_aParams;

    /**
     * The ops.
     */
    private byte[] m_abOps;

    /**
     * The constants used by the Ops.
     */
    Constant[] m_aconstLocal;

    /**
     * The constant registry used while assembling the Ops.
     */
    transient ConstantRegistry m_registry;

    /**
     * The method's code (for assembling new code).
     */
    private transient Code m_code;

    /**
     * The max number of registers used by the method. Calculated from the ops.
     */
    transient int m_cVars;

    /**
     * The max number of scopes used by the method. Calculated from the ops.
     */
    transient int m_cScopes;

    /**
     * True iff the method has been marked as "native". This is not part of the persistent method
     * structure; it exists only to support the prototype interpreter implementation.
     */
    private transient boolean m_fNative;

    /**
     * True iff the method has been marked as "transient". This is not part of the persistent method
     * structure; it exists only to support the prototype interpreter implementation.
     */
    private transient boolean m_fTransient;

    /**
     * Cached information about whether this method has code.
     */
    private transient Boolean m_FHasCode;

    /**
     * Cached information about whether this method uses its super.
     */
    private transient Boolean m_FUsesSuper;

    /**
     * Cached information about whether any singleton constants used by this method have been
     * fully initialized.
     */
    private transient boolean m_fInitialized;

    /**
     * Cached method for the construct-finally that goes with this method, iff this method is a
     * construct function that has a finally.
     */
    private transient MethodStructure m_structFinally;
    }
