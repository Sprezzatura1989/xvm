package org.xvm.runtime;


import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import java.util.concurrent.ConcurrentHashMap;

import java.util.function.Function;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Component.Contribution;
import org.xvm.asm.Component.Composition;
import org.xvm.asm.Component.Format;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.Constants.Access;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;
import org.xvm.asm.PropertyStructure;

import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.MethodBody;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.MethodInfo;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.PropertyInfo;
import org.xvm.asm.constants.SignatureConstant;
import org.xvm.asm.constants.TerminalTypeConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.TypeInfo;

import org.xvm.runtime.ObjectHandle.DeferredCallHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.ObjectHandle.GenericHandle;

import org.xvm.runtime.Utils.BinaryAction;
import org.xvm.runtime.Utils.InPlacePropertyBinary;
import org.xvm.runtime.Utils.InPlacePropertyUnary;
import org.xvm.runtime.Utils.UnaryAction;

import org.xvm.runtime.template.annotations.xFutureVar.FutureHandle;

import org.xvm.runtime.template.InterfaceProxy;
import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xException;
import org.xvm.runtime.template.xFunction.FullyBoundHandle;
import org.xvm.runtime.template.xObject;
import org.xvm.runtime.template.xOrdered;
import org.xvm.runtime.template.xRef;
import org.xvm.runtime.template.xRef.RefHandle;
import org.xvm.runtime.template.xService.ServiceHandle;
import org.xvm.runtime.template.xString;
import org.xvm.runtime.template.xVar;

import org.xvm.runtime.template.collections.xTuple;


/**
 * ClassTemplate represents a run-time class.
 */
public abstract class ClassTemplate
        implements OpSupport
    {
    // construct the template
    public ClassTemplate(TemplateRegistry templates, ClassStructure structClass)
        {
        f_templates = templates;
        f_struct    = structClass;
        f_sName     = structClass.getIdentityConstant().getPathString();

        // calculate the parents (inheritance and "native")
        ClassStructure structSuper = null;

        Contribution contribExtend = structClass.findContribution(Composition.Extends);
        if (contribExtend != null)
            {
            IdentityConstant idExtend = (IdentityConstant) contribExtend.
                getTypeConstant().getDefiningConstant();

            structSuper = (ClassStructure) idExtend.getComponent();
            }

        f_structSuper = structSuper;
        }

    /**
     * Initialize properties, methods and functions declared at the "top" layer.
     */
    public void initDeclared()
        {
        }

    /**
     * Obtain the canonical type that is represented by this {@link ClassTemplate}
     */
    public TypeConstant getCanonicalType()
        {
        return f_struct.getCanonicalType();
        }

    /**
     * Obtain the ClassConstant for this {@link ClassTemplate}.
     */
    public ClassConstant getClassConstant()
        {
        return (ClassConstant) f_struct.getIdentityConstant();
        }

    /**
     * Obtain the inception ClassConstant that is represented by this {@link ClassTemplate}.
     *
     * Most of the time the inception class is the same as the structure's class, except
     * for a number of native rebased interfaces (Ref, Var, Const, Enum).
     *
     * Note: the following should always hold true:
     *      getInceptionClass().asTypeConstant().getOpSupport() == this;
     */
    protected ClassConstant getInceptionClassConstant()
        {
        return getClassConstant();
        }

    /**
     * @return a super template; null only for Object
     */
    public ClassTemplate getSuper()
        {
        ClassTemplate templateSuper = m_templateSuper;
        if (templateSuper == null)
            {
            if (f_structSuper == null)
                {
                if (f_sName.equals("Object"))
                    {
                    return null;
                    }
                templateSuper = m_templateSuper = xObject.INSTANCE;
                }
            else
                {
                templateSuper = m_templateSuper =
                    f_templates.getTemplate(f_structSuper.getIdentityConstant());
                }
            }
        return templateSuper;
        }

    /**
     * Obtain the canonical ClassComposition for this template.
     */
    public ClassComposition getCanonicalClass()
        {
        ClassComposition clz = m_clazzCanonical;
        if (clz == null)
            {
            m_clazzCanonical = clz = ensureCanonicalClass();
            }
        return clz;
        }

    /**
     * Ensure the canonical ClassComposition for this template.
     */
    protected ClassComposition ensureCanonicalClass()
        {
        return ensureClass(getCanonicalType());
        }

    /**
     * Produce a ClassComposition for this template using the actual types for formal parameters.
     *
     * @param pool        the ConstantPool to place a potentially created new type into
     * @param typeParams  the type parameters
     */
    public ClassComposition ensureParameterizedClass(ConstantPool pool, TypeConstant... typeParams)
        {
        TypeConstant typeInception = pool.ensureParameterizedTypeConstant(
            getInceptionClassConstant().getType(), typeParams).normalizeParameters(pool);

        TypeConstant typeMask = getCanonicalType().adoptParameters(pool, typeParams);

        return ensureClass(typeInception, typeMask);
        }

    /**
     * Produce a ClassComposition using the specified actual type.
     *
     * Note: the passed type should be fully resolved and normalized
     *       (all formal parameters resolved)
     */
    public ClassComposition ensureClass(TypeConstant typeActual)
        {
        ClassConstant constInception = getInceptionClassConstant();

        if (typeActual.getDefiningConstant().equals(constInception))
            {
            return ensureClass(typeActual, typeActual);
            }

        // replace the TerminalType of the typeActual with the inception type
        Function<TypeConstant, TypeConstant> transformer =
                new Function<TypeConstant, TypeConstant>()
            {
            public TypeConstant apply(TypeConstant type)
                {
                return type instanceof TerminalTypeConstant
                    ? constInception.getType()
                    : type.replaceUnderlying(typeActual.getConstantPool(), this);
                }
            };

        return ensureClass(transformer.apply(typeActual), typeActual);
        }

    /**
     * Produce a ClassComposition for this type using the specified actual (inception) type
     * and the revealed (mask) type.
     *
     * Note: the passed inception and mask types should be fully resolved and normalized
     *       (all formal parameters resolved)
     * Note2: the following should always hold true: typeInception.getOpSupport() == this;
     */
    protected ClassComposition ensureClass(TypeConstant typeInception, TypeConstant typeMask)
        {
        ConstantPool pool = typeInception.getConstantPool();

        assert !typeInception.isAccessSpecified();
        assert !typeMask.isAccessSpecified();
        assert typeInception.normalizeParameters(pool).equals(typeInception);
        assert typeMask.normalizeParameters(pool).equals(typeMask);

        ClassComposition clz = m_mapCompositions.computeIfAbsent(typeInception, (typeI) ->
            {
            OpSupport support = typeI.isAnnotated() && typeI.isIntoVariableType()
                    ? typeI.getOpSupport(f_templates)
                    : this;

            return new ClassComposition(support, typeI);
            });

        return typeMask.equals(typeInception) ? clz : clz.maskAs(typeMask);
        }

    /**
     * @return true iff a newly constructed object should be marked as "immutable"
     */
    protected boolean isConstructImmutable()
        {
        return false;
        }

    protected ClassTemplate getChildTemplate(String sName)
        {
        return f_templates.getTemplate(f_sName + '.' + sName);
        }

    /**
     * Find the specified property in this template or direct inheritance chain.
     *
     * @return the specified property of null
     */
    protected PropertyStructure findProperty(String sPropName)
        {
        // we cannot use the TypeInfo here, since the TypeInfo will be build based on the information
        // provided by this method's caller; however, we can assume a simple class hierarchy
        ClassStructure struct = f_struct;
        do
            {
            PropertyStructure prop = (PropertyStructure) struct.getChild(sPropName);
            if (prop != null)
                {
                return prop;
                }
            struct = struct.getSuper();
            }
        while (struct != null);

        return null;
        }

    @Override
    public int hashCode()
        {
        return f_sName.hashCode();
        }

    @Override
    public boolean equals(Object obj)
        {
        // class templates are singletons
        return this == obj;
        }

    @Override
    public String toString()
        {
        return f_struct.toString();
        }


    // ----- constructions  ------------------------------------------------------------------------

    /**
     * Specifies whether or not this template uses a GenericHandle for its objects.
     */
    public boolean isGenericHandle()
        {
        return true;
        }

    /**
     * Specifies whether or not this template represents a service.
     */
    public boolean isService()
        {
        return false;
        }

    /**
     * Create an object handle for the specified constant and push it on the frame's local stack.
     *
     * @param frame     the current frame
     * @param constant  the constant
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int createConstHandle(Frame frame, Constant constant)
        {
        return frame.raiseException("Unknown constant:" + constant);
        }

    /**
     * Construct an {@link ObjectHandle} of the specified class with the specified constructor.
     *
     * The following steps are to be performed:
     * <ul>
     *   <li>Invoke the auto-generated initializer for the "inception" type;
     *   <li>Invoke the specified constructor, potentially calling some super constructors
     *       passing "this:struct" as a target
     *   <li>Invoke all finalizers in the inheritance chain starting at the base passing
     *       "this:private" as a target
     * </ul>
     *
     * @param frame        the current frame
     * @param constructor  the MethodStructure for the constructor
     * @param clazz        the target class
     * @param hParent      (optional) parent instance
     * @param ahVar        the construction parameters
     * @param iReturn      the register id to place the created handle into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int construct(Frame frame, MethodStructure constructor, ClassComposition clazz,
                         ObjectHandle hParent, ObjectHandle[] ahVar, int iReturn)
        {
        ObjectHandle hStruct = createStruct(frame, clazz);

        if (hParent != null)
            {
            // strictly speaking a static child doesn't need to hold the parent's ref,
            // but that decision (not to hold) could be deferred or even statistically implemented,
            // since there could be benefits (e.g. during debugging) for knowing the parent
            ((GenericHandle) hStruct).setField(GenericHandle.OUTER, hParent);
            }

        return callConstructor(frame, constructor, clazz.ensureAutoInitializer(), hStruct, ahVar, iReturn);
        }

    /**
     * Continuation of the {@link #construct} sequence.
     */
    public int callConstructor(Frame frame, MethodStructure constructor, MethodStructure methodAI,
                               ObjectHandle hStruct, ObjectHandle[] ahVar, int iReturn)
        {
        // assume that we have class D with an auto-generated initializer (AI), a constructor (CD),
        // and a finalizer (FD) that extends B with a constructor (CB) and a finalizer (FB)
        // the call sequence should be:
        //
        //  ("new" op-code) => AI -> CD => CB -> FB -> FD -> "assign" (continuation)
        //
        // -> indicates a call via continuation
        // => indicates a call via Construct op-code
        //
        // we need to create the call chain in the revers order;
        // the very last frame should also assign the resulting new object

        Frame frameCD = frame.createFrame1(constructor, hStruct, ahVar, Op.A_IGNORE);

        // we need a non-null anchor (see Frame#chainFinalizer)
        FullyBoundHandle hFD = frameCD.m_hfnFinally = Utils.makeFinalizer(constructor, ahVar);

        frameCD.addContinuation(frameCaller ->
            {
            List<String> listUnassigned;
            if ((listUnassigned = hStruct.validateFields()) != null)
                {
                return frameCaller.raiseException(
                    xException.unassignedFields(frameCaller, listUnassigned));
                }

            if (isConstructImmutable())
                {
                ExceptionHandle hEx = makeImmutable(frameCaller, hStruct);
                if (hEx != null)
                    {
                    return frame.raiseException(hEx);
                    }
                }

            ObjectHandle hPublic = hStruct.ensureAccess(Access.PUBLIC);
            if (hPublic instanceof ServiceHandle)
                {
                frameCaller.f_context.setService((ServiceHandle) hPublic);
                }

            return hFD.callChain(frameCaller, hPublic, frame0 ->
                frame0.assignValue(iReturn, hPublic));
            });

        if (methodAI == null)
            {
            return frame.callInitialized(frameCD);
            }

        Frame frameInit = frame.createFrame1(methodAI, hStruct, Utils.OBJECTS_NONE, Op.A_IGNORE);

        frameInit.addContinuation(frameCaller -> frameCaller.callInitialized(frameCD));

        return frame.callInitialized(frameInit);
        }

    /**
     * Create an ObjectHandle of the "struct" access for the specified natural class.
     *
     * @param frame  the current frame
     * @param clazz  the ClassComposition for the newly created handle
     *
     * @return the newly allocated handle
     */
    protected ObjectHandle createStruct(Frame frame, ClassComposition clazz)
        {
        assert clazz.getTemplate() == this &&
             (f_struct.getFormat() == Format.CLASS ||
              f_struct.getFormat() == Format.CONST ||
              f_struct.getFormat() == Format.ENUMVALUE);

        clazz = clazz.ensureAccess(Access.STRUCT);

        return new GenericHandle(clazz);
        }


    // ----- mutability ----------------------------------------------------------------------------

    /**
     * Make the specified object handle immutable.
     *
     * @param frame    the current frame
     * @param hTarget  the object handle
     *
     * @return null if the operation succeeded, an exception to throw otherwise
     */
    protected ExceptionHandle makeImmutable(Frame frame, ObjectHandle hTarget)
        {
        if (hTarget.isMutable())
            {
            hTarget.makeImmutable();

            if (hTarget instanceof GenericHandle)
                {
                TypeComposition           clz       = hTarget.getComposition();
                Map<Object, ObjectHandle> mapFields = ((GenericHandle) hTarget).getFields();
                for (Map.Entry<Object, ObjectHandle> entry : mapFields.entrySet())
                    {
                    Object       nid    = entry.getKey();
                    ObjectHandle hValue = entry.getValue();
                    if (hValue != null && hValue.isMutable() && !clz.isLazy(nid))
                        {
                        ExceptionHandle hEx = hValue.getTemplate().makeImmutable(frame, hValue);
                        if (hEx != null)
                            {
                            return hEx;
                            }
                        }
                    }
                }
            }
        return null;
        }

    /**
     * Create a proxy handle that could be sent over the service boundaries.
     *
     * @param ctx        the service context that the mutable object "belongs" to
     * @param hTarget    the mutable object handle that needs to be proxied
     * @param typeProxy  the [revealed] type of the proxy handle
     *
     * @return a new ObjectHandle to replace the mutable object with or null
     */
    public ObjectHandle createProxyHandle(ServiceContext ctx, ObjectHandle hTarget,
                                          TypeConstant typeProxy)
        {
        if (typeProxy != null && typeProxy.isInterfaceType())
            {
            assert hTarget.getType().isA(typeProxy);

            TypeInfo info = typeProxy.ensureTypeInfo();

            // ensure the methods only use constants, services or proxy-able interfaces
            for (Map.Entry<MethodConstant, MethodInfo> entry : info.getMethods().entrySet())
                {
                MethodConstant idMethod   = entry.getKey();
                MethodInfo     infoMethod = entry.getValue();
                if (idMethod.getNestedDepth() == 2 && infoMethod.isVirtual())
                    {
                    MethodBody      body   = infoMethod.getHead();
                    MethodStructure method = body.getMethodStructure();
                    for (int i = 0, c = method.getParamCount(); i < c; i++)
                        {
                        TypeConstant typeParam = method.getParam(i).getType();
                        if (!isProxyable(typeParam))
                            {
                            return null;
                            }
                        }
                    }
                }

            for (Map.Entry<PropertyConstant, PropertyInfo> entry : info.getProperties().entrySet())
                {
                PropertyConstant idProp   = entry.getKey();
                PropertyInfo     infoProp = entry.getValue();
                if (idProp.getNestedDepth() == 1 && infoProp.isVirtual())
                    {
                    if (!isProxyable(infoProp.getType()))
                        {
                        return null;
                        }
                    }
                }

            ClassComposition clzTarget = (ClassComposition) hTarget.getComposition();
            ProxyComposition clzProxy  = clzTarget.ensureProxyComposition(typeProxy);

            return InterfaceProxy.makeHandle(clzProxy, ctx, hTarget);
            }
        return null;
        }

    /**
     * @return true iff objects of the specified type could be proxied across the service boundary
     */
    protected boolean isProxyable(TypeConstant type)
        {
        return type.isConstant()
            || type.isInterfaceType()
            || (type.isSingleUnderlyingClass(false)
             && type.getSingleUnderlyingClass(false).getComponent().getFormat() == Format.SERVICE);
        }


    // ----- invocations ---------------------------------------------------------------------------

    /**
     * Invoke a method with zero or one return value on the specified target.
     *
     * @param frame    the current frame
     * @param chain    the CallChain representing the target method
     * @param hTarget  the target handle
     * @param ahVar    the invocation parameters
     * @param iReturn  the register id to place the result of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invoke1(Frame frame, CallChain chain, ObjectHandle hTarget,
                        ObjectHandle[] ahVar, int iReturn)
        {
        return frame.invoke1(chain, 0, hTarget, ahVar, iReturn);
        }

    /**
     * Invoke a method with a return value of Tuple.
     *
     * @param frame    the current frame
     * @param chain    the CallChain representing the target method
     * @param hTarget  the target handle
     * @param ahVar    the invocation parameters
     * @param iReturn  the register id to place the result of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokeT(Frame frame, CallChain chain, ObjectHandle hTarget,
                        ObjectHandle[] ahVar, int iReturn)
        {
        return frame.invokeT(chain, 0, hTarget, ahVar, iReturn);
        }

    /**
     * Invoke a method with more than one return value.
     *
     * @param frame     the current frame
     * @param chain     the CallChain representing the target method
     * @param hTarget   the target handle
     * @param ahVar     the invocation parameters
     * @param aiReturn  the array of register ids to place the results of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokeN(Frame frame, CallChain chain, ObjectHandle hTarget,
                        ObjectHandle[] ahVar, int[] aiReturn)
        {
        return frame.invokeN(chain, 0, hTarget, ahVar, aiReturn);
        }

    /**
     * Invoke a native method with exactly one argument and zero or one return value.
     *
     * @param frame    the current frame
     * @param method   the target method
     * @param hTarget  the target handle
     * @param hArg     the invocation arguments
     * @param iReturn  the register id to place the result of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL}, {@link Op#R_EXCEPTION},
     *         or {@link Op#R_BLOCK} values
     */
    public int invokeNative1(Frame frame, MethodStructure method,
                             ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        throw new IllegalStateException("Unknown method: " + method + " on " + this);
        }

    /**
     * Invoke a native method with zero or more than one argument and zero or one return value.
     *
     * @param frame    the current frame
     * @param method   the target method
     * @param hTarget  the target handle
     * @param ahArg    the invocation arguments
     * @param iReturn  the register id to place the result of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL}, {@link Op#R_EXCEPTION},
     *         or {@link Op#R_BLOCK} values
     */
    public int invokeNativeN(Frame frame, MethodStructure method,
                             ObjectHandle hTarget, ObjectHandle[] ahArg, int iReturn)
        {
        switch (ahArg.length)
            {
            case 0:
                if (method.getName().equals("toString"))
                    {
                    return buildStringValue(frame, hTarget, iReturn);
                    }
                break;

            case 3:
                if (method.getName().equals("equals"))
                    {
                    return frame.assignValue(iReturn,
                            xBoolean.makeHandle(ahArg[1] == ahArg[2]));
                    }
                break;
            }

        throw new IllegalStateException("Compilation failed for method: " + f_sName + "#"
                + method.getIdentityConstant().getSignature().getValueString());
        }

    /**
     * Invoke a native method with any number of argument and return value of a Tuple.
     *
     * @param frame    the current frame
     * @param method   the target method
     * @param hTarget  the target handle
     * @param ahArg    the invocation arguments
     * @param iReturn  the register id to place the resulting Tuple into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL}, {@link Op#R_EXCEPTION},
     *         or {@link Op#R_BLOCK} values
     */
    public int invokeNativeT(Frame frame, MethodStructure method,
                             ObjectHandle hTarget, ObjectHandle[] ahArg, int iReturn)
        {
        if (method.getParamCount() == 1)
            {
            switch (method.getReturnCount())
                {
                case 0:
                    switch (invokeNative1(frame, method, hTarget, ahArg[0], Op.A_IGNORE))
                        {
                        case Op.R_NEXT:
                            return frame.assignValue(iReturn, xTuple.H_VOID);

                        case Op.R_EXCEPTION:
                            return Op.R_EXCEPTION;

                        default:
                            throw new IllegalStateException();
                        }

                case 1:
                    switch (invokeNative1(frame, method, hTarget, ahArg[0], Op.A_STACK))
                        {
                        case Op.R_NEXT:
                            return frame.assignTuple(iReturn, new ObjectHandle[]{frame.popStack()});

                        case Op.R_EXCEPTION:
                            return Op.R_EXCEPTION;

                        default:
                            throw new IllegalStateException();
                        }

                default:
                    // create a temporary frame with N registers; call invokeNativeNN into it
                    // and then convert the results into a Tuple
                    throw new UnsupportedOperationException();
                }
            }
        else
            {
            switch (method.getReturnCount())
                {
                case 0:
                    switch (invokeNativeN(frame, method, hTarget, ahArg, Op.A_IGNORE))
                        {
                        case Op.R_NEXT:
                            return frame.assignValue(iReturn, xTuple.H_VOID);

                        case Op.R_EXCEPTION:
                            return Op.R_EXCEPTION;

                        default:
                            throw new IllegalStateException();
                        }

                case 1:
                    switch (invokeNativeN(frame, method, hTarget, ahArg, Op.A_STACK))
                        {
                        case Op.R_NEXT:
                            return frame.assignTuple(iReturn, new ObjectHandle[]{frame.popStack()});

                        case Op.R_EXCEPTION:
                            return Op.R_EXCEPTION;

                        default:
                            throw new IllegalStateException();
                        }

                default:
                    // create a temporary frame with N registers; call invokeNativeNN into it
                    // and then convert the results into a Tuple
                    throw new UnsupportedOperationException();
                }
            }
        }

    /**
     * Invoke a native method with any number of arguments and more than one return value.
     *
     * @param frame     the current frame
     * @param method    the target method
     * @param hTarget   the target handle
     * @param ahArg     the invocation arguments
     * @param aiReturn  the array of register ids to place the results of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL}, {@link Op#R_EXCEPTION},
     *         or {@link Op#R_BLOCK} values
     */
    public int invokeNativeNN(Frame frame, MethodStructure method,
                              ObjectHandle hTarget, ObjectHandle[] ahArg, int[] aiReturn)
        {
        throw new IllegalStateException("Unknown method: " + method + " on " + this);
        }


    // ----- property operations -------------------------------------------------------------------

    /**
     * Retrieve a property value.
     *
     * @param frame    the current frame
     * @param hTarget  the target handle
     * @param idProp   the property id
     * @param iReturn  the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int getPropertyValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (idProp == null)
            {
            throw new IllegalStateException(f_sName);
            }

        TypeComposition clzTarget = hTarget.getComposition();

        CallChain chain = clzTarget.getPropertyGetterChain(idProp);

        if (chain == null)
            {
            return frame.raiseException("Unknown property: " + idProp.getValueString());
            }

        if (chain.isNative())
            {
            return invokeNativeGet(frame, idProp.getName(), hTarget, iReturn);
            }

        if (clzTarget.isStruct() || chain.isField())
            {
            return getFieldValue(frame, hTarget, idProp, iReturn);
            }

        MethodStructure method = chain.getTop();
        ObjectHandle[]  ahVar  = new ObjectHandle[method.getMaxVars()];

        if (hTarget.isInflated(idProp))
            {
            hTarget = ((GenericHandle) hTarget).getField(idProp);
            }

        return frame.invoke1(chain, 0, hTarget, ahVar, iReturn);
        }

    /**
     * Retrieve a field value.
     *
     * @param frame    the current frame
     * @param hTarget  the target handle
     * @param idProp   the property id
     * @param iReturn  the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int getFieldValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (idProp == null)
            {
            throw new IllegalStateException(f_sName);
            }

        GenericHandle hThis  = (GenericHandle) hTarget;
        ObjectHandle  hValue = hThis.getField(idProp);

        if (hValue == null)
            {
            String sErr;
            if (hThis.isInjected(idProp))
                {
                TypeInfo     info = hThis.getType().ensureTypeInfo();
                PropertyInfo prop = info.findProperty(idProp);

                hValue = frame.f_context.f_container.getInjectable(
                        frame, idProp.getName(), prop.getType());
                if (hValue != null)
                    {
                    if (hValue instanceof DeferredCallHandle)
                        {
                        ((DeferredCallHandle) hValue).addContinuation(frameCaller ->
                            {
                            hThis.setField(idProp, frameCaller.peekStack());
                            return Op.R_NEXT;
                            });
                        }
                    else
                        {
                        hThis.setField(idProp, hValue);
                        }
                    return frame.assignValue(iReturn, hValue);
                    }
                sErr = "Unknown injectable property ";
                }
            else
                {
                sErr = hThis.containsField(idProp) ?
                        "Un-initialized property \"" : "Invalid property \"";
                }

            return frame.raiseException(xException.illegalState(frame, sErr + idProp + '"'));
            }

        if (hTarget.isInflated(idProp))
            {
            RefHandle hRef = (RefHandle) hValue;
            assert !(hRef instanceof FutureHandle);
            return ((xRef) hRef.getTemplate()).getReferent(frame, hRef, iReturn);
            }

        return frame.assignValue(iReturn, hValue);
        }

    /**
     * Set a property value.
     *
     * @param frame    the current frame
     * @param hTarget  the target handle
     * @param idProp   the property id
     * @param hValue   the new value
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int setPropertyValue(Frame frame, ObjectHandle hTarget,
                                PropertyConstant idProp, ObjectHandle hValue)
        {
        if (idProp == null)
            {
            throw new IllegalStateException(f_sName);
            }

        if (hTarget.isStruct())
            {
            return setFieldValue(frame, hTarget, idProp, hValue);
            }

        if (!hTarget.isMutable())
            {
            return frame.raiseException(xException.immutableObject(frame));
            }

        CallChain chain = hTarget.getComposition().getPropertySetterChain(idProp);

        if (chain == null)
            {
            return frame.raiseException("Unknown property: " + idProp.getValueString());
            }

        if (chain.isNative())
            {
            return invokeNativeSet(frame, hTarget, idProp.getName(), hValue);
            }

        if (chain.isField())
            {
            return setFieldValue(frame, hTarget, idProp, hValue);
            }

        MethodStructure method = chain.getTop();
        ObjectHandle[] ahVar = new ObjectHandle[method.getMaxVars()];
        ahVar[0] = hValue;

        if (hTarget.isInflated(idProp))
            {
            hTarget = ((GenericHandle) hTarget).getField(idProp);
            }

        return frame.invoke1(chain, 0, hTarget, ahVar, Op.A_IGNORE);
        }

    /**
     * Set a field value.
     *
     * @param frame    the current frame
     * @param hTarget  the target handle
     * @param idProp   the property name
     * @param hValue   the new value
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int setFieldValue(Frame frame, ObjectHandle hTarget,
                             PropertyConstant idProp, ObjectHandle hValue)
        {
        if (idProp == null)
            {
            throw new IllegalStateException(f_sName);
            }

        GenericHandle hThis = (GenericHandle) hTarget;

        assert hThis.containsField(idProp);

        if (hThis.isInflated(idProp))
            {
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            ((xRef) hRef.getTemplate()).setReferent(frame, hRef, hValue);
            }
        else
            {
            hThis.setField(idProp, hValue);
            }
        return Op.R_NEXT;
        }

    /**
     * Invoke a native property "get" operation.
     *
     * @param frame     the current frame
     * @param sPropName the property name
     * @param hTarget   the target handle
     * @param iReturn   the register id to place the result of invocation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        if (hTarget.getType().containsGenericParam(sPropName))
            {
            TypeConstant type = hTarget.getType().resolveGenericType(sPropName);

            return frame.assignValue(iReturn, type.getTypeHandle());
            }

        throw new IllegalStateException("Unknown property: " + sPropName + " on " + this);
        }

    /**
     * Invoke a native property "set" operation.
     *
     * @param frame     the current frame
     * @param sPropName the property name
     * @param hTarget   the target handle
     * @param hValue    the new property value
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokeNativeSet(Frame frame, ObjectHandle hTarget, String sPropName, ObjectHandle hValue)
        {
        throw new IllegalStateException("Unknown property: " + sPropName + " on " + this);
        }

    /**
     * Increment the property value and retrieve the new value.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param iReturn    the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePreInc(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarPreInc(frame, hRef, iReturn);
            }
        return new InPlacePropertyUnary(
            UnaryAction.INC, this, hTarget, idProp, false, iReturn).doNext(frame);
        }

    /**
     * Retrieve the property value and then increment it.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param iReturn    the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePostInc(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarPostInc(frame, hRef, iReturn);
            }
        return new InPlacePropertyUnary(
            UnaryAction.INC, this, hTarget, idProp, true, iReturn).doNext(frame);
        }

    /**
     * Decrement the property value and retrieve the new value.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param iReturn    the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePreDec(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarPreDec(frame, hRef, iReturn);
            }
        return new InPlacePropertyUnary(
            UnaryAction.DEC, this, hTarget, idProp, false, iReturn).doNext(frame);
        }

    /**
     * Retrieve the property value and then decrement it.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param iReturn    the register id to place a result of the operation into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePostDec(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarPostDec(frame, hRef, iReturn);
            }
        return new InPlacePropertyUnary(
            UnaryAction.DEC, this, hTarget, idProp, true, iReturn).doNext(frame);
        }

    /**
     * Add the specified argument to the property value.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyAdd(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarAdd(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.ADD, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Subtract the specified argument from the property value.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertySub(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarSub(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.SUB, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Multiply the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyMul(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarMul(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.MUL, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Divide the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyDiv(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarDiv(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.DIV, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Mod the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyMod(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarMod(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.MOD, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Shift-left the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyShl(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarShl(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.SHL, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Shift-right the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyShr(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarShr(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.SHR, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Unsigned shift-right the property value by the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyShrAll(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarShrAll(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.USHR, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * "And" the property value with the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyAnd(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarAnd(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.AND, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * "Or" the property value with the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyOr(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarOr(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.OR, this, hTarget, idProp, hArg).doNext(frame);
        }

    /**
     * Exclusive-or the property value with the specified argument.
     *
     * @param frame      the current frame
     * @param hTarget    the target handle
     * @param idProp  the property name
     * @param hArg       the argument handle
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int invokePropertyXor(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
        {
        if (hTarget.isInflated(idProp))
            {
            GenericHandle hThis = (GenericHandle) hTarget;
            RefHandle     hRef  = (RefHandle) hThis.getField(idProp);
            return hRef.getVarSupport().invokeVarXor(frame, hRef, hArg);
            }

        return new InPlacePropertyBinary(
            BinaryAction.XOR, this, hTarget, idProp, hArg).doNext(frame);
        }


    // ----- Ref operations ------------------------------------------------------------------------

    /**
     * Create a property Ref or Var for the specified target and property.
     *
     * @param frame       the ConstantPool to place a potentially created new type into
     * @param hTarget    the target handle
     * @param idProp  the property constant
     * @param fRO        true iff a Ref is required; Var otherwise
     * @param iReturn    the register to place the result in
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int createPropertyRef(Frame frame, ObjectHandle hTarget,
                                 PropertyConstant idProp, boolean fRO, int iReturn)
        {
        GenericHandle hThis = (GenericHandle) hTarget;

        if (!hThis.containsField(idProp))
            {
            throw new IllegalStateException("Unknown property: (" + f_sName + ")." + idProp);
            }

        if (hTarget.isInflated(idProp))
            {
            RefHandle hRef = (RefHandle) hThis.getField(idProp);
            return frame.assignValue(iReturn, hRef);
            }

        ConstantPool pool         = frame.poolContext();
        TypeConstant typeReferent = idProp.getType().resolveGenerics(pool, hTarget.getType());

        ClassComposition clzRef = fRO
            ? xRef.INSTANCE.ensureParameterizedClass(pool, typeReferent)
            : xVar.INSTANCE.ensureParameterizedClass(pool, typeReferent);

        RefHandle hRef = new RefHandle(clzRef, hThis, idProp);
        return frame.assignValue(iReturn, hRef);
        }

    // ----- support for equality and comparison ---------------------------------------------------

    /**
     * Compare for equality two object handles that both belong to the specified class.
     *
     * @param frame    the current frame
     * @param clazz    the class to use for the equality check
     * @param hValue1  the first value
     * @param hValue2  the second value
     * @param iReturn  the register id to place a Boolean result into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int callEquals(Frame frame, ClassComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xBoolean.TRUE);
            }

        // if there is an "equals" function that is not native (on the Object itself),
        // we need to call it
        TypeConstant    type       = clazz.getType();
        MethodStructure functionEq = type.findCallable(frame.poolContext().sigEquals());
        if (functionEq != null && !functionEq.isNative())
            {
            ObjectHandle[] ahVars = new ObjectHandle[functionEq.getMaxVars()];
            ahVars[0] = type.getTypeHandle();
            ahVars[1] = hValue1;
            ahVars[2] = hValue2;
            return frame.call1(functionEq, null, ahVars, iReturn);
            }

        return callEqualsImpl(frame, clazz, hValue1, hValue2, iReturn);
        }

    /**
     * Default implementation for "equals"; overridden only by xConst.
     */
    protected int callEqualsImpl(Frame frame,  ClassComposition clazz,
                                 ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        return frame.assignValue(iReturn, xBoolean.FALSE);
        }

    /**
     * Compare for order two object handles that both belong to the specified class.
     *
     * @param frame    the current frame
     * @param clazz    the class to use for the comparison test
     * @param hValue1  the first value
     * @param hValue2  the second value
     * @param iReturn  the register id to place an Ordered result into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public int callCompare(Frame frame, ClassComposition clazz,
                           ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xOrdered.EQUAL);
            }

        // if there is an "compare" function, we need to call it
        TypeConstant    type        = clazz.getType();
        MethodStructure functionCmp = type.findCallable(frame.poolContext().sigCompare());
        if (functionCmp != null && !functionCmp.isNative())
            {
            ObjectHandle[] ahVars = new ObjectHandle[functionCmp.getMaxVars()];
            ahVars[0] = type.getTypeHandle();
            ahVars[1] = hValue1;
            ahVars[2] = hValue2;
            return frame.call1(functionCmp, null, ahVars, iReturn);
            }

        return callCompareImpl(frame, clazz, hValue1, hValue2, iReturn);
        }

    /**
     * Default implementation for "compare"; overridden only by xConst.
     */
    protected int callCompareImpl(Frame frame,  ClassComposition clazz,
                                 ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        return frame.raiseException("No implementation for \"compare()\" function at " + f_sName);
        }

    /**
     * Compare for identity equality two object handles that both associated with this template.
     *
     * More specifically, the ObjectHandles must either be the same runtime object, or the objects
     * that they represent are both immutable and structurally identical (see Ref.equals).
     *
     * Note: this method is inherently native; it must be answered without calling any natural code
     *
     * @param hValue1  the first value
     * @param hValue2  the second value
     *
     * @return true iff the identities are equal
     */
    public boolean compareIdentity(ObjectHandle hValue1, ObjectHandle hValue2)
        {
        return hValue1 == hValue2 ||
               isGenericHandle() && GenericHandle.compareIdentity(
                        (GenericHandle) hValue1, (GenericHandle) hValue2);
        }


    // ---- OpSupport implementation ---------------------------------------------------------------

    @Override
    public ClassTemplate getTemplate(TypeConstant type)
        {
        return this;
        }

    @Override
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "add", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeSub(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "sub", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeMul(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "mul", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeDiv(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "div", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "mod", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeShl(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "shiftLeft", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeShr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "shiftRight", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeShrAll(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "shiftAllRight", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeAnd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "and", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeOr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "or", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeXor(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "xor", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeDivMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int[] aiReturn)
        {
        return getOpChain(hTarget, "divmod", hArg).invoke(frame, hTarget, hArg, aiReturn);
        }

    @Override
    public int invokeDotDot(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        return getOpChain(hTarget, "through", hArg).invoke(frame, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeNeg(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return getOpChain(hTarget, "neg", null).invoke(frame, hTarget, iReturn);
        }

    @Override
    public int invokeCompl(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return getOpChain(hTarget, "not", null).invoke(frame, hTarget, iReturn);
        }

    @Override
    public int invokeNext(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return getOpChain(hTarget, "nextValue", null).invoke(frame, hTarget, iReturn);
        }

    @Override
    public int invokePrev(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return getOpChain(hTarget, "prevValue", null).invoke(frame, hTarget, iReturn);
        }

    /**
     * @return a call chain for the specified op; throw if none exists
     */
    protected CallChain getOpChain(ObjectHandle hTarget, String sOp, ObjectHandle hArg)
        {
        CallChain chain = findOpChain(hTarget, sOp, hArg);
        if (chain == null)
            {
            throw new IllegalStateException("Invalid op for " + this);
            }
        return chain;
        }

    /**
     * @return a call chain for the specified op or null if none exists
     */
    public CallChain findOpChain(ObjectHandle hTarget, String sOp)
        {
        TypeComposition clz  = hTarget.getComposition();
        TypeInfo        info = clz.getType().ensureTypeInfo();

        Set<MethodConstant> setMethods = info.findOpMethods(sOp, sOp, 0);
        switch (setMethods.size())
            {
            case 0:
                return null;

            case 1:
                {
                MethodConstant idMethod = setMethods.iterator().next();
                return clz.getMethodCallChain(idMethod.getSignature());
                }

            default:
                // soft assert
                System.err.println("Ambiguous \"" + sOp + "\" operation on " +
                        hTarget.getType().getValueString());
                return null;
            }
        }
    /**
     * @return a call chain for the specified op and argument or null if none exists
     */
    public CallChain findOpChain(ObjectHandle hTarget, String sOp, ObjectHandle hArg)
        {
        TypeComposition clz  = hTarget.getComposition();
        TypeInfo        info = clz.getType().ensureTypeInfo();

        Set<MethodConstant> setMethods = info.findOpMethods(sOp, sOp, hArg == null ? 0 : 1);
        switch (setMethods.size())
            {
            case 0:
                return null;

            case 1:
                {
                MethodConstant idMethod = setMethods.iterator().next();
                return clz.getMethodCallChain(idMethod.getSignature());
                }

            default:
                {
                if (hArg != null)
                    {
                    TypeConstant typeArg = hArg.getType();
                    for (MethodConstant idMethod : setMethods)
                        {
                        SignatureConstant sig       = idMethod.getSignature();
                        TypeConstant      typeParam = sig.getRawParams()[0];

                        if (typeArg.isA(typeParam))
                            {
                            return clz.getMethodCallChain(sig);
                            }
                        }
                    }

                // soft assert
                System.err.println("Ambiguous \"" + sOp + "\" operation on " +
                        hTarget.getType().getValueString());
                return null;
                }
            }
        }

    /**
     * @return a call chain for the specified op and arguments or null if none exists
     */
    public CallChain findOpChain(ObjectHandle hTarget, String sOp, ObjectHandle[] ahArg)
        {
        TypeComposition clz   = hTarget.getComposition();
        TypeInfo        info  = clz.getType().ensureTypeInfo();
        int             cArgs = ahArg.length;

        Set<MethodConstant> setMethods = info.findOpMethods(sOp, sOp, cArgs);
        switch (setMethods.size())
            {
            case 0:
                return null;

            case 1:
                {
                MethodConstant idMethod = setMethods.iterator().next();
                return clz.getMethodCallChain(idMethod.getSignature());
                }

            default:
                {
                NextMethod:
                for (MethodConstant idMethod : setMethods)
                    {
                    SignatureConstant sig = idMethod.getSignature();

                    for (int i = 0; i < cArgs; i++)
                        {
                        ObjectHandle hArg      = ahArg[i];
                        TypeConstant typeArg   = hArg.getType();
                        TypeConstant typeParam = sig.getRawParams()[i];

                        if (!typeArg.isA(typeParam))
                            {
                            continue NextMethod;
                            }
                        }
                    return clz.getMethodCallChain(sig);
                    }

                // soft assert
                System.err.println("Ambiguous \"" + sOp + "\" operation on " +
                        hTarget.getType().getValueString());
                return null;
                }
            }
        }


    // ----- toString() support --------------------------------------------------------------------

    /**
     * Build a String handle for a human readable representation of the target handle.
     *
     * @param frame    the current frame
     * @param hTarget  the target
     * @param iReturn  the register id to place a String result into
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    protected int buildStringValue(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return frame.assignValue(iReturn, xString.makeHandle(hTarget.toString()));
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * @return the constant pool associated with the corresponding structure
     */
    public ConstantPool pool()
        {
        return f_struct.getConstantPool();
        }

    // =========== TEMPORARY ========

    /**
     * Mark the specified method as native.
     */
    protected void markNativeMethod(String sName, String[] asParamType, String[] asRetType)
        {
        TypeConstant[] atypeArg = f_templates.f_adapter.getTypeConstants(this, asParamType);
        TypeConstant[] atypeRet = f_templates.f_adapter.getTypeConstants(this, asRetType);

        MethodStructure method = f_struct.findMethod(sName, atypeArg, atypeRet);
        if (method == null)
            {
            System.err.println("Missing method " + f_sName + "." + sName +
                Arrays.toString(asParamType) + "->" + Arrays.toString(asRetType));
            }
        else
            {
            method.markNative();
            }
        }

    /**
     * Mark the specified property as native.
     *
     * Note: this also makes the property "calculated" (no storage)
     */
    protected void markNativeProperty(String sPropName)
        {
        PropertyStructure prop = findProperty(sPropName);
        if (prop == null)
            {
            System.err.println("Missing property " + f_sName + "." + sPropName);
            }
        else
            {
            prop.markNative();

            MethodStructure methGetter = prop.getGetter();
            if (methGetter != null)
                {
                methGetter.markNative();
                }

            MethodStructure methSetter = prop.getGetter();
            if (methSetter != null)
                {
                methSetter.markNative();
                }
            }
        }


    // ----- constants and fields ------------------------------------------------------------------

    public static String[] VOID    = new String[0];
    public static String[] THIS    = new String[] {"this"};
    public static String[] OBJECT  = new String[] {"Object"};
    public static String[] INT     = new String[] {"Int64"};
    public static String[] STRING  = new String[] {"String"};
    public static String[] BOOLEAN = new String[] {"Boolean"};

    /**
     * The TemplateRegistry.
     */
    public final TemplateRegistry f_templates;

    /**
     * Globally known ClassTemplate name (e.g. Boolean or annotations.LazyVar)
     */
    public final String f_sName;

    /**
     * The underlying ClassStructure.
     */
    public final ClassStructure f_struct;

    /**
     * The ClassStructure of the super class.
     */
    protected final ClassStructure f_structSuper;

    /**
     * The ClassTemplate of the super class.
     */
    protected ClassTemplate m_templateSuper;


    // ----- caches ------

    /**
     * Canonical type composition.
     */
    protected ClassComposition m_clazzCanonical;

    /**
     * A cache of "instantiate-able" ClassCompositions keyed by the "inception type". Most of the
     * time the revealed type is identical to the inception type and is defined by a
     * {@link ClassConstant} referring to a concrete natural class (not an interface).
     *
     * The only exceptions are the native types (e.g. Ref, Service), for which the inception type is
     * defined by a {@link org.xvm.asm.constants.NativeRebaseConstant} class constant and the
     * revealed type refers to the corresponding natural interface.
     *
     * We assume that for a given template, there will never be two instantiate-able classes with
     * the same inception type, but different revealed type. OTOH, the ClassComposition may
     * hide (or mask) its original identity via the {@link TypeComposition#maskAs(TypeConstant)}
     * operation and later reveal it back. All those transformations are handled by the
     * ClassComposition itself and are not known or controllable by the ClassTemplate.
     */
    private Map<TypeConstant, ClassComposition> m_mapCompositions = new ConcurrentHashMap<>();
    }
