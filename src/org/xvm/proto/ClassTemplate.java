package org.xvm.proto;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Component;
import org.xvm.asm.Constant;
import org.xvm.asm.Constants;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.MultiMethodStructure;
import org.xvm.asm.PropertyStructure;

import org.xvm.asm.constants.StringConstant;
import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.ClassTypeConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.TypeConstant;
import org.xvm.asm.constants.UnresolvedTypeConstant;

import org.xvm.proto.ObjectHandle.ExceptionHandle;
import org.xvm.proto.ObjectHandle.GenericHandle;

import org.xvm.proto.template.collections.xArray;
import org.xvm.proto.template.collections.xTuple;
import org.xvm.proto.template.Const;
import org.xvm.proto.template.Enum;
import org.xvm.proto.template.Enum.EnumHandle;
import org.xvm.proto.template.Function;
import org.xvm.proto.template.Function.FullyBoundHandle;
import org.xvm.proto.template.Ref.RefHandle;
import org.xvm.proto.template.Service;
import org.xvm.proto.template.xBoolean;
import org.xvm.proto.template.xException;
import org.xvm.proto.template.xObject;
import org.xvm.proto.template.xOrdered;
import org.xvm.proto.template.xString;
import org.xvm.proto.template.xType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * ClassTemplate represents a run-time class.
 *
 * @author gg 2017.02.23
 */
public abstract class ClassTemplate
    {
    public final TypeSet f_types;
    public final ClassStructure f_struct;

    public final String f_sName; // globally known ClassTemplate name (e.g. Boolean or annotations.AtomicRef)

    public final TypeComposition f_clazzCanonical; // public non-parameterized class
    public final List<String> f_listGenericParams; // param names

    public final ClassTemplate f_templateSuper;
    public final ClassTemplate f_templateCategory; // a native category

    // ----- caches ------

    // cache of TypeCompositions
    protected Map<List<Type>, TypeComposition> m_mapCompositions = new HashMap<>();

    // cache of relationships
    protected enum Relation {EXTENDS, IMPLEMENTS, INCOMPATIBLE}
    protected Map<ClassTemplate, Relation> m_mapRelations = new HashMap<>();

    // construct the template
    public ClassTemplate(TypeSet types, ClassStructure structClass)
        {
        f_types = types;
        f_struct = structClass;
        f_sName = structClass.getIdentityConstant().getPathString();

        List<Map.Entry<StringConstant, TypeConstant>> listFormalTypes =
                structClass.getTypeParamsAsList();
        int cParams = listFormalTypes.size();

        List<String> listParams;
        if (cParams == 0)
            {
            listParams = Collections.EMPTY_LIST;
            }
        else
            {
            listParams = new ArrayList<>(cParams);

            for (int i = 0; i < cParams; i++)
                {
                listParams.add(listFormalTypes.get(i).getKey().getValue());
                }
            }
        f_listGenericParams = listParams;

        // calculate the parents (inheritance and "native")
        ClassStructure structSuper = null;
        ClassTemplate templateSuper = null;
        ClassTemplate templateCategory = null;

        Optional<ClassStructure.Contribution> opt = structClass.getContributionsAsList().stream().
                filter((c) -> c.getComposition().equals(ClassStructure.Composition.Extends)).findFirst();

        if (opt.isPresent())
            {
            ClassConstant constClass = (ClassConstant) opt.get().getClassConstant().getClassConstant();
            structSuper = (ClassStructure) constClass.getComponent();
            templateSuper = f_types.getTemplate(structSuper.getIdentityConstant());
            }

        if (structSuper == null)
            {
            if (!f_sName.equals("Object"))
                {
                templateSuper = xObject.INSTANCE;
                }
            }

        if (structSuper == null || structClass.getFormat() != structSuper.getFormat())
            {
            switch (structClass.getFormat())
                {
                case SERVICE:
                    templateCategory = Service.INSTANCE;
                    break;

                case CONST:
                    templateCategory = Const.INSTANCE;
                    break;

                case ENUM:
                    templateCategory = Enum.INSTANCE;
                    break;
                }
            }

        f_templateSuper = templateSuper;
        f_templateCategory = templateCategory;
        f_clazzCanonical = createCanonicalClass();
        }

    protected TypeComposition createCanonicalClass()
        {
        Map<StringConstant, TypeConstant> mapParams = f_struct.getTypeParams();
        Map<String, Type> mapParamsActual;

        if (mapParams.isEmpty())
            {
            mapParamsActual = Collections.EMPTY_MAP;
            }
        else
            {
            mapParamsActual = new HashMap<>(mapParams.size());
            for (StringConstant constName : mapParams.keySet())
                {
                TypeConstant constType = mapParams.get(constName);
                Type typeObject = f_types.resolveParameterType(constType, Collections.EMPTY_MAP);
                mapParamsActual.put(constName.getValue(), typeObject);
                }
            }

        return new TypeComposition(this, mapParamsActual, true);
        }

    public boolean isRootObject()
        {
        return f_templateSuper == null && f_struct.getFormat() == Component.Format.CLASS;
        }

    /**
     * Initialize properties, methods and functions declared at the "top" layer.
     *
     * TODO: remove; should be called from constructors
     */
    public void initDeclared()
        {
        markNativeMethod("to", VOID, STRING);
        }

    // does this template extend that?
    public boolean extends_(ClassTemplate that)
        {
        if (this == that)
            {
            return true;
            }

        Relation relation = m_mapRelations.get(that);
        if (relation != null)
            {
            return relation == Relation.EXTENDS;
            }

        ClassTemplate templateSuper = f_templateSuper;
        while (templateSuper != null)
            {
            m_mapRelations.put(templateSuper, Relation.EXTENDS);

            // there is just one template instance per name
            if (templateSuper == that)
                {
                return true;
                }
            templateSuper = templateSuper.f_templateSuper;
            }

        m_mapRelations.put(that, Relation.INCOMPATIBLE);
        return false;
        }

    public ClassTemplate getSuper()
        {
        return f_templateSuper;
        }

    public boolean isService()
        {
        return f_struct.getFormat() == Component.Format.SERVICE;
        }

    public boolean isSingleton()
        {
        return f_struct.isStatic();
        }

    public PropertyStructure getProperty(String sName)
        {
        return (PropertyStructure) f_struct.getChild(sName);
        }

    public ClassTypeConstant getTypeConstant()
        {
        return ((ClassConstant) f_struct.getIdentityConstant()).asTypeConstant();
        }

    // get a method declared at this template level
    public MethodStructure getDeclaredMethod(String sName, TypeConstant[] atParam, TypeConstant[] atReturn)
        {
        MultiMethodStructure mms = (MultiMethodStructure) f_struct.getChild(sName);
        if (mms != null)
            {
            nextMethod:
            for (MethodStructure method : ((List<MethodStructure>) (List) mms.children()))
                {
                MethodConstant constMethod = method.getIdentityConstant();

                TypeConstant[] atParamTest = constMethod.getRawParams();
                TypeConstant[] atReturnTest = constMethod.getRawReturns();

                if (atParam != null) // temporary work around; TODO: remove
                    {
                    int cParams = atParamTest.length;
                    if (cParams != atParam.length)
                        {
                        continue;
                        }

                    for (int i = 0, c = atParamTest.length; i < c; i++)
                        {
                        // compensate for "function"
                        if (atParamTest[i].getValueString().contains("function"))
                            {
                            continue;
                            }
                        if (!atParamTest[i].equals(atParam[i]))
                            {
                            continue nextMethod;
                            }
                        }
                    }

                if (atReturn != null) // temporary work around; TODO: remove
                    {
                    int cReturns = atReturnTest.length;
                    if (cReturns != atReturn.length)
                        {
                        // compensate for the return type of Void
                        if (atReturn.length == 0 && atReturnTest[0].getValueString().contains("Void"))
                            {
                            return method;
                            }
                        continue;
                        }

                    for (int i = 0, c = atReturnTest.length; i < c; i++)
                        {
                        if (!atReturnTest[i].equals(atReturn[i]))
                            {
                            continue nextMethod;
                            }
                        }
                    }
                return method;
                }
            }

        return null;
        }

    // get a method declared at this template level
    // TODO: replace MethodConstant with MethodIdConstant
    public MethodStructure getDeclaredMethod(MethodConstant constMethod)
        {
        MultiMethodStructure mms = (MultiMethodStructure) f_struct.getChild(constMethod.getName());

        if (mms != null)
            {
            //        Optional<Component> opt = mms.children().stream().filter(
            //                method -> method.getIdentityConstant().equals(constMethod)).findAny();
            //
            //        return opt.isPresent() ? (MethodStructure) opt.get() : null;

            TypeConstant[] atParam = constMethod.getRawParams();
            TypeConstant[] atReturn = constMethod.getRawReturns();

            for (MethodStructure method : (List<MethodStructure>) (List) mms.children())
                {
                MethodConstant constTest = method.getIdentityConstant();
                TypeConstant[] atParamTest = constTest.getRawParams();
                TypeConstant[] atReturnTest = constTest.getRawReturns();

                if (Arrays.equals(atParam, atParamTest) && Arrays.equals(atReturn, atReturnTest))
                    {
                    return method;
                    }

                // TODO: remove
                if (mms.children().size() == 1)
                    {
                    System.out.println("\n\t\t****** Signature mismatch for " +
                            constMethod + " at " + f_sName + "\n");
                    return method;
                    }
                }
            }
        return null;
        }

    // find one of the "equals" or "compare"  methods
    public MethodStructure findCompareFunction(String sName, TypeConstant[] atRet)
        {
        ClassTemplate template = this;
        TypeConstant typeThis = getTypeConstant();
        TypeConstant[] atArg = new TypeConstant[] {typeThis, typeThis};
        do
            {
            MethodStructure method = getDeclaredMethod(sName, atArg, atRet);
            if  (method != null && method.isStatic())
                {
                return method;
                }
            template = template.f_templateSuper;
            }
        while (template != null);
        return null;
        }

    // produce a TypeComposition for this template by resolving the generic types
    public TypeComposition resolveClass(ClassTypeConstant constClassType, Map<String, Type> mapActual)
        {
        assert constClassType.getClassConstant().getPathString().equals(f_sName);

        List<TypeConstant> listParams = constClassType.getTypeConstants();

        int cParams = listParams.size();
        if (cParams == 0)
            {
            return f_clazzCanonical;
            }

        assert f_listGenericParams.size() == listParams.size();

        Map<String, Type> mapActualParams = new HashMap<>();
        int ix = 0;
        for (String sParamName : f_listGenericParams)
            {
            mapActualParams.put(sParamName,
                    f_types.resolveParameterType(listParams.get(ix++), mapActual));
            }
        return ensureClass(mapActualParams);
        }

    // produce a TypeComposition for this template using the actual types for formal parameters
    public TypeComposition ensureClass(Map<String, Type> mapParams)
        {
        assert mapParams.size() == f_listGenericParams.size() || this instanceof xTuple;

        if (mapParams.isEmpty())
            {
            return f_clazzCanonical;
            }

        // sort the parameters by name and use the list of sorted (by formal name) types as a key
        Map<String, Type> mapSorted = mapParams.size() > 1 ?
                new TreeMap<>(mapParams) : mapParams;
        List<Type> key = new ArrayList<>(mapSorted.values());

        return m_mapCompositions.computeIfAbsent(key,
                (x) -> new TypeComposition(this, mapParams, false));
        }

    @Override
    public int hashCode()
        {
        return f_sName.hashCode();
        }

    @Override
    public boolean equals(Object obj)
        {
        // type compositions are singletons
        return this == obj;
        }

    @Override
    public String toString()
        {
        return f_struct.toString();
        }

    // ---- OpCode support: construction and initialization -----

    // create a RefHandle for the specified class
    // sName is an optional ref name
    // TODO: consider moving this method up to xRef
    public RefHandle createRefHandle(TypeComposition clazz, String sName)
        {
        throw new IllegalStateException("Invalid op for " + f_sName);
        }

    // assign (Int i = 5;)
    // @return an immutable handle or null if this type doesn't take that constant
    public ObjectHandle createConstHandle(Constant constant, ObjectHeap heap)
        {
        return null;
        }

    // return a handle with this:struct access
    protected ObjectHandle createStruct(Frame frame, TypeComposition clazz)
        {
        assert f_struct.getTypeParamsAsList().isEmpty();
        assert f_struct.getFormat() == Component.Format.CLASS ||
               f_struct.getFormat() == Component.Format.CONST;

        return new GenericHandle(clazz, clazz.ensureStructType());
        }

    // invoke the default constructors, then the specified constructor,
    // then finalizers; change this:struct handle to this:public
    // return one of the Op.R_ values
    public int construct(Frame frame, MethodStructure constructor,
                         TypeComposition clazz, ObjectHandle[] ahVar, int iReturn)
        {
        ObjectHandle hStruct = createStruct(frame, clazz); // this:struct

        // assume that we have C1 extending C0 with default constructors (DC),
        // regular constructors (RC), and finalizers (F);
        // the call sequence should be:
        //
        //  ("new" op-code) => DC0 -> DC1 -> RC1 => RC0 -> F0 -> F1 -> "assign" (continuation)
        //
        // -> indicates a call via continuation
        // => indicates a call via Construct op-cod
        //
        // we need to create the call chain in the revers order;
        // the very last frame should also assign the resulting new object

        // this:private -> this:public
        Frame.Continuation contAssign =
                frameCaller -> frameCaller.assignValue(iReturn,
                    hStruct.f_clazz.ensureAccess(hStruct, Constants.Access.PUBLIC));

        Frame frameRC1 = frame.f_context.createFrame1(frame, constructor, hStruct, ahVar, Frame.RET_UNUSED);

        Frame frameDC0 = clazz.callDefaultConstructors(frame, hStruct, ahVar,
                frameCaller -> frameCaller.call(frameRC1));

        // we need a non-null anchor (see Frame#chainFinalizer)
        frameRC1.m_hfnFinally = makeFinalizer(constructor, hStruct, ahVar); // hF1

        frameRC1.setContinuation(frameCaller ->
            {
            if (isConstructImmutable())
                {
                hStruct.makeImmutable();
                }

            FullyBoundHandle hF = frameRC1.m_hfnFinally;

            // this:struct -> this:private
            return hF.callChain(frameCaller, Constants.Access.PRIVATE, contAssign);
            });

        return frame.call(frameDC0 == null ? frameRC1 : frameDC0);
        }

    public FullyBoundHandle makeFinalizer(MethodStructure constructor,
                                          ObjectHandle hStruct, ObjectHandle[] ahArg)
        {
        MethodStructure methodFinally = f_types.f_adapter.getFinalizer(constructor);

        return methodFinally == null ? FullyBoundHandle.NO_OP :
                Function.makeHandle(methodFinally).bindAll(hStruct, ahArg);
        }

    protected boolean isConstructImmutable()
        {
        return this instanceof Const;
        }

    // ----- OpCode support ------

    // invoke with a zero or one return value
    public int invoke1(Frame frame, ObjectHandle hTarget, MethodStructure method, ObjectHandle[] ahVar, int iReturn)
        {
        return frame.call1(method, hTarget, ahVar, iReturn);
        }

    // invoke with more than one return value
    public int invokeN(Frame frame, ObjectHandle hTarget, MethodStructure method, ObjectHandle[] ahVar, int[] aiReturn)
        {
        return frame.callN(method, hTarget, ahVar, aiReturn);
        }

    // invokeNative property "get" operation
    public int invokeNativeGet(Frame frame, ObjectHandle hTarget, PropertyStructure property, int iReturn)
        {
        throw new IllegalStateException("Unknown property getter: (" + f_sName + ")." + property.getName());
        }

    // invokeNative property "set" operation
    public int invokeNativeSet(Frame frame, ObjectHandle hTarget, PropertyStructure property, ObjectHandle hValue)
        {
        throw new IllegalStateException("Unknown property setter: (" + f_sName + ")." + property.getName());
        }

    // invokeNative with exactly one argument and zero or one return value
    // place the result into the specified frame register
    // return one of the Op.R_ values
    public int invokeNative1(Frame frame, MethodStructure method,
                             ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        throw new IllegalStateException("Unknown method: (" + f_sName + ")." + method);
        }

    // invokeNative with zero or more than one arguments and zero or one return values
    // return one of the Op.R_ values
    public int invokeNativeN(Frame frame, MethodStructure method,
                             ObjectHandle hTarget, ObjectHandle[] ahArg, int iReturn)
        {
        switch (ahArg.length)
            {
            case 0:
                if (method.getName().equals("to"))
                    {
                    // how to differentiate; check the method's return type?
                    return buildStringValue(frame, hTarget, iReturn);
                    }
            }

        throw new IllegalStateException("Unknown method: (" + f_sName + ")." + method);
        }

    // invokeNative with zero or more arguments and more than one return values
    // return one of the Op.R_ values
    public int invokeNativeNN(Frame frame, MethodStructure method,
                              ObjectHandle hTarget, ObjectHandle[] ahArg, int[] aiReturn)
        {
        throw new IllegalStateException("Unknown method: (" + f_sName + ")." + method);
        }

    // Add operation; place the result into the specified frame register
    // return one of the Op.R_ values
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        throw new IllegalStateException("Invalid op for " + f_sName);
        }

    // Neg operation
    // return one of the Op.R_ values
    public int invokeNeg(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        throw new IllegalStateException("Invalid op for " + f_sName);
        }

    // ---- OpCode support: register or property operations -----

    // helper method
    private ObjectHandle extractPropertyValue(GenericHandle hTarget, PropertyStructure property)
        {
        if (property == null)
            {
            throw new IllegalStateException("Invalid op for " + f_sName);
            }

        ObjectHandle hProp = hTarget.m_mapFields.get(property.getName());

        if (hProp == null)
            {
            throw new IllegalStateException((hTarget.m_mapFields.containsKey(property.getName()) ?
                    "Un-initialized property \"" : "Invalid property \"") + property + '"');
            }
        return hProp;
        }

    // increment the property value and place the result into the specified frame register
    // return either R_NEXT or R_EXCEPTION
    public int invokePreInc(Frame frame, ObjectHandle hTarget,
                            PropertyStructure property, int iReturn)
        {
        GenericHandle hThis = (GenericHandle) hTarget;

        ObjectHandle hProp = extractPropertyValue(hThis, property);

        if (isRef(property))
            {
            return getRefTemplate(property).invokePreInc(frame, hProp, null, iReturn);
            }

        int nResult = hProp.f_clazz.f_template.invokePreInc(frame, hProp, null, Frame.RET_LOCAL);
        if (nResult == Op.R_EXCEPTION)
            {
            return nResult;
            }

        ObjectHandle hPropNew = frame.getFrameLocal();
        hThis.m_mapFields.put(property.getName(), hPropNew);

        return frame.assignValue(iReturn, hPropNew);
        }

    // place the property value into the specified frame register and increment it
    // return either R_NEXT or R_EXCEPTION
    public int invokePostInc(Frame frame, ObjectHandle hTarget,
                             PropertyStructure property, int iReturn)
        {
        GenericHandle hThis = (GenericHandle) hTarget;

        ObjectHandle hProp = extractPropertyValue(hThis, property);

        if (isRef(property))
            {
            return getRefTemplate(property).invokePostInc(frame, hProp, null, iReturn);
            }

        int nResult = hProp.f_clazz.f_template.invokePostInc(frame, hProp, null, Frame.RET_LOCAL);
        if (nResult == Op.R_EXCEPTION)
            {
            return nResult;
            }

        ObjectHandle hPropNew = frame.getFrameLocal();
        hThis.m_mapFields.put(property.getName(), hPropNew);

        return frame.assignValue(iReturn, hProp);
        }

    // ----- OpCode support: property operations -----

    // get a property value into the specified register
    // return R_NEXT, R_CALL or R_EXCEPTION
    public int getPropertyValue(Frame frame, ObjectHandle hTarget,
                                PropertyStructure property, int iReturn)
        {
        if (property == null)
            {
            throw new IllegalStateException(f_sName);
            }

        if (isNativeGetter(property))
            {
            return invokeNativeGet(frame, hTarget, property, iReturn);
            }

        MethodStructure method = hTarget.isStruct() ? null : Adapter.getGetter(property);
        if (method == null)
            {
            return getFieldValue(frame, hTarget, property, iReturn);
            }

        ObjectHandle[] ahVar = new ObjectHandle[frame.f_adapter.getVarCount(method)];

        return frame.call1(method, hTarget, ahVar, iReturn);
        }

    public int getFieldValue(Frame frame, ObjectHandle hTarget,
                             PropertyStructure property, int iReturn)
        {
        if (property == null)
            {
            throw new IllegalStateException(f_sName);
            }

        String sName = property.getName();

        if (isGenericType(sName))
            {
            Type type = hTarget.f_clazz.getActualType(sName);

            return frame.assignValue(iReturn, xType.makeHandle(type));
            }

        GenericHandle hThis = (GenericHandle) hTarget;
        ObjectHandle hValue = hThis.m_mapFields.get(sName);

        if (hValue == null)
            {
            String sErr;
            if (isInjectable(property))
                {
                TypeComposition clz = hThis.f_clazz.resolveClass(property.getType());

                hValue = frame.f_context.f_container.getInjectable(sName, clz);
                if (hValue != null)
                    {
                    hThis.m_mapFields.put(sName, hValue);
                    return frame.assignValue(iReturn, hValue);
                    }
                sErr = "Unknown injectable property ";
                }
            else
                {
                sErr = hThis.m_mapFields.containsKey(sName) ?
                        "Un-initialized property \"" : "Invalid property \"";
                }

            frame.m_hException = xException.makeHandle(sErr + sName + '"');
            return Op.R_EXCEPTION;
            }

        if (isRef(property))
            {
            try
                {
                hValue = ((RefHandle) hValue).get();
                }
            catch (ExceptionHandle.WrapperException e)
                {
                frame.m_hException = e.getExceptionHandle();
                return Op.R_EXCEPTION;
                }
            }

        return frame.assignValue(iReturn, hValue);
        }

    // set a property value
    public int setPropertyValue(Frame frame, ObjectHandle hTarget,
                                PropertyStructure property, ObjectHandle hValue)
        {
        if (property == null)
            {
            throw new IllegalStateException(f_sName);
            }

        ExceptionHandle hException = null;
        if (!hTarget.isMutable())
            {
            hException = xException.makeHandle("Immutable object: " + hTarget);
            }
        else if (isCalculated(property))
            {
            hException = xException.makeHandle("Read-only property: " + property.getName());
            }

        if (hException == null)
            {
            if (isNativeSetter(property))
                {
                return invokeNativeSet(frame, hTarget, property, hValue);
                }

            MethodStructure method = hTarget.isStruct() ? null : Adapter.getSetter(property);
            if (method == null)
                {
                hException = setFieldValue(hTarget, property, hValue);
                }
            else
                {
                ObjectHandle[] ahVar = new ObjectHandle[frame.f_adapter.getVarCount(method)];
                ahVar[1] = hValue;

                return frame.call1(method, hTarget, ahVar, Frame.RET_UNUSED);
                }
            }

        if (hException != null)
            {
            frame.m_hException = hException;
            return Op.R_EXCEPTION;
            }
        return Op.R_NEXT;
        }

    public ExceptionHandle setFieldValue(ObjectHandle hTarget,
                                         PropertyStructure property, ObjectHandle hValue)
        {
        if (property == null)
            {
            throw new IllegalStateException(f_sName);
            }

        GenericHandle hThis = (GenericHandle) hTarget;

        assert hThis.m_mapFields.containsKey(property.getName());

        if (isRef(property))
            {
            return ((RefHandle) hThis.m_mapFields.get(property.getName())).set(hValue);
            }

        hThis.m_mapFields.put(property.getName(), hValue);
        return null;
        }

    // ----- support for equality and comparison ------

    // compare for equality two object handles that both belong to the specified class
    // return R_NEXT or R_EXCEPTION
    public int callEquals(Frame frame, TypeComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        // if there is an "equals" function, we need to call it
        MethodStructure functionEquals = findCompareFunction("equals", xBoolean.TYPES);
        if (functionEquals != null)
            {
            return frame.call1(functionEquals, null,
                    new ObjectHandle[]{hValue1, hValue2}, iReturn);
            }

        // only Const classes have an automatic implementation;
        // for everyone else it's either a native method or a ref equality
        return frame.assignValue(iReturn, xBoolean.makeHandle(hValue1 == hValue2));
        }

    // compare for order two object handles that both belong to the specified class
    // return R_NEXT or R_EXCEPTION
    public int callCompare(Frame frame, TypeComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        // if there is an "compare" function, we need to call it
        MethodStructure functionCompare = findCompareFunction("compare", xOrdered.TYPES);
        if (functionCompare != null)
            {
            return frame.call1(functionCompare, null,
                    new ObjectHandle[]{hValue1, hValue2}, iReturn);
            }

        // only Const and Enum classes have automatic implementations
        switch (f_struct.getFormat())
            {
            case ENUMVALUE:
                EnumHandle hV1 = (EnumHandle) hValue1;
                EnumHandle hV2 = (EnumHandle) hValue2;

                return frame.assignValue(iReturn,
                        xOrdered.makeHandle(hV1.getValue() - hV2.getValue()));

            default:
                throw new IllegalStateException(
                        "No implementation for \"compare()\" function at " + f_sName);
            }
        }

    // build the String representation of the target handle
    // returns R_NEXT, R_CALL or R_EXCEPTION
    public int buildStringValue(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        return frame.assignValue(iReturn, xString.makeHandle(hTarget.toString()));
        }

    // ----- Op-code support: array operations -----

    // get a handle to an array for the specified class
    // returns R_NEXT or R_EXCEPTION
    public int createArrayStruct(Frame frame, TypeComposition clzArray,
                                 long cCapacity, int iReturn)
        {
        if (cCapacity < 0 || cCapacity > Integer.MAX_VALUE)
            {
            frame.m_hException = xException.makeHandle("Invalid array size: " + cCapacity);
            return Op.R_EXCEPTION;
            }

        return frame.assignValue(iReturn, xArray.makeHandle(clzArray, cCapacity));
        }

    // ----- to be replaced when the structure support is added

    protected boolean isInjectable(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_fInjectable;
        }

    protected boolean isAtomic(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_fAtomic;
        }

    protected boolean isCalculated(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_fCalculated;
        }

    protected boolean isNativeGetter(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_fNativeGetter;
        }

    protected boolean isNativeSetter(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_fNativeSetter;
        }

    protected boolean isRef(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info != null && info.m_templateRef != null;
        }

    protected ClassTemplate getRefTemplate(PropertyStructure property)
        {
        PropertyInfo info = property.getInfo();
        return info == null ? null : info.m_templateRef;
        }

    protected boolean isGenericType(String sProperty)
        {
        return f_listGenericParams.contains(sProperty);
        }

    // represents a chain of invocation
    public static class CallChain
        {
        // an optimization for a single method chain
        private MethodStructure m_method;

        // a list of methods (only if m_method == null)
        private List<MethodStructure> m_listMethods;

        // a property representing the field access
        private PropertyStructure m_property;

        // get vs. set
        private boolean m_fGet;

        // a constructor for a method chain
        public CallChain(List<MethodStructure> listMethods)
            {
            if (listMethods.size() == 1)
                {
                m_method = listMethods.get(0);
                }
            else
                {
                m_listMethods = listMethods;
                }
            }

        // a constructor for a property access chain
        public CallChain(List<MethodStructure> listMethods, PropertyStructure property, boolean fGet)
            {
            this(listMethods);

            m_property = property;
            m_fGet = fGet;
            }

        public MethodStructure getTop()
            {
            if (m_method == null)
                {
                return m_listMethods.get(0);
                }
            return null;
            }
        }

    // =========== TEMPORARY ========

    public void markNativeMethod(String sName, String[] asParamType)
        {
        markNativeMethod(sName, asParamType, VOID);
        }

    public void markNativeMethod(String sName, String[] asParamType, String[] asRetType)
        {
        ensureMethodInfo(sName, asParamType, asRetType).m_fNative = true;
        }

    public MethodInfo ensureMethodInfo(String sName, String[] asParam)
        {
        return ensureMethodInfo(sName, asParam, VOID);
        }

    public MethodInfo ensureMethodInfo(String sName, String[] asParam, String[] asRet)
        {
        MethodStructure method = f_types.f_adapter.getMethod(this, sName, asParam, asRet);
        if (method == null)
            {
            throw new IllegalArgumentException("Method is not defined: " + f_sName + '#' + sName);
            }
        return ensureMethodInfo(method);
        }

    public MethodInfo ensureMethodInfo(MethodStructure method)
        {
        MethodInfo info = method.getInfo();
        if (info == null)
            {
            method.setInfo(info = new MethodInfo(method));
            }
        return info;
        }

    public void markInjectable(String sPropName)
        {
        ensurePropertyInfo(sPropName).m_fInjectable = true;
        }

    public void markCalculated(String sPropName)
        {
        ensurePropertyInfo(sPropName).m_fCalculated = true;
        }

    public void markAtomicRef(String sPropName)
        {
        ensurePropertyInfo(sPropName).markAtomic();
        }

    public void markNativeGetter(String sPropName)
        {
        ensurePropertyInfo(sPropName).m_fNativeGetter = true;
        }

    public void markNativeSetter(String sPropName)
        {
        ensurePropertyInfo(sPropName).m_fNativeSetter = true;
        }

    public MethodInfo ensureGetter(String sPropName)
        {
        PropertyStructure prop = getProperty(sPropName);

        return ensureMethodInfo(Adapter.getGetter(prop));
        }

    public MethodInfo ensureSetter(String sPropName)
        {
        PropertyStructure prop = getProperty(sPropName);

        return ensureMethodInfo(Adapter.getSetter(prop));
        }

    public static class MethodInfo
        {
        public final MethodStructure f_struct;
        public boolean m_fNative;
        public Op[] m_aop;
        public int m_cVars;
        public int m_cScopes = 1;
        public MethodInfo m_mtFinally;

        public MethodInfo(MethodStructure struct)
            {
            f_struct = struct;
            }
        }

    public PropertyInfo ensurePropertyInfo(String sPropName)
        {
        PropertyStructure property = getProperty(sPropName);

        PropertyInfo info = property.getInfo();
        if (info == null)
            {
            property.setInfo(info = new PropertyInfo(f_types, property));
            }
        return info;
        }

    public static class PropertyInfo
        {
        protected final TypeSet f_types;
        protected final PropertyStructure f_property;
        protected boolean m_fAtomic;
        protected boolean m_fInjectable;
        protected boolean m_fCalculated;
        protected boolean m_fNativeGetter;
        protected boolean m_fNativeSetter;
        protected ClassTemplate m_templateRef;

        public PropertyInfo(TypeSet types, PropertyStructure property)
            {
            f_types = types;
            f_property = property;
            }

        protected void markAtomic()
            {
            m_fAtomic = true;

            TypeConstant constType = f_property.getType();
            if (constType instanceof UnresolvedTypeConstant)
                {
                constType = ((UnresolvedTypeConstant) constType).getResolvedConstant();
                }
            if (constType instanceof ClassTypeConstant &&
                    ((ClassTypeConstant) constType).getClassConstant().getName().equals("Int64"))
                {
                markAsRef("annotations.AtomicIntNumber");
                }
            else
                {
                markAsRef("annotations.AtomicRef");
                }
            }

        public void markAsRef(String sRefClassName)
            {
            m_templateRef = f_types.getTemplate(sRefClassName);
            }
        }

    public static String[] VOID = new String[0];
    public static String[] OBJECT = new String[]{"Object"};
    public static String[] INT = new String[]{"Int64"};
    public static String[] BOOLEAN = new String[]{"Boolean"};
    public static String[] STRING = new String[]{"String"};
    }
