package org.xvm.runtime;


import java.sql.Timestamp;

import java.util.concurrent.CompletableFuture;

import org.xvm.asm.ConstantPool;
import org.xvm.asm.Constants.Access;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.asm.constants.SignatureConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xConst;
import org.xvm.runtime.template.xFunction;
import org.xvm.runtime.template.xOrdered;
import org.xvm.runtime.template.xRef.RefHandle;
import org.xvm.runtime.template.xString.StringHandle;

import org.xvm.runtime.template.annotations.xFutureVar;

import org.xvm.runtime.template.types.xProperty.PropertyHandle;


/**
 * Various helpers.
 */
public abstract class Utils
    {
    public final static int[] ARGS_NONE = new int[0];
    public final static ObjectHandle[] OBJECTS_NONE = new ObjectHandle[0];

    public static SignatureConstant SIG_CONSTRUCT; // no-parameter constructor
    public static SignatureConstant SIG_TO_STRING; // to<String>()

    public static void log(Frame frame, String sMsg)
        {
        if (sMsg.charAt(0) == '\n')
            {
            System.out.println();
            sMsg = sMsg.substring(1);
            }

        ServiceContext ctx;
        long lFiberId;

        if (frame == null)
            {
            ctx = ServiceContext.getCurrentContext();
            lFiberId = -1;
            }
        else
            {
            ctx = frame.f_context;
            lFiberId = frame.f_fiber.getId();
            }

        System.out.println(new Timestamp(System.currentTimeMillis())
            + " " + ctx + ", fiber " + lFiberId + ": " + sMsg);
        }

    public static void registerGlobalSignatures(ConstantPool pool)
        {
        TypeConstant tString = pool.getImplicitlyImportedComponent("String").getIdentityConstant().asTypeConstant();

        TypeConstant[] atVoid = ConstantPool.NO_TYPES;
        TypeConstant[] atString = new TypeConstant[] {tString};

        SIG_CONSTRUCT = pool.ensureSignatureConstant("construct", atVoid, atVoid);
        SIG_TO_STRING = pool.ensureSignatureConstant("to", atVoid, atString);
        }

    /**
     * Ensure that the specified array of arguments is of the specified size.
     *
     * @param ahArg  the array of arguments
     * @param cVars  the desired array size
     *
     * @return the array of the desired size containing all the arguments
     */
    public static ObjectHandle[] ensureSize(ObjectHandle[] ahArg, int cVars)
        {
        int cArgs = ahArg.length;
        if (cArgs == cVars)
            {
            return ahArg;
            }

        if (cArgs < cVars)
            {
            ObjectHandle[] ahVar = new ObjectHandle[cVars];
            System.arraycopy(ahArg, 0, ahVar, 0, cArgs);
            return ahVar;
            }

        throw new IllegalArgumentException("Requested size " + cVars +
            " is less than the array size " + cArgs);
        }

    /**
     * Create a FullyBoundHandle representing a finalizer of the specified constructor.
     *
     * @param constructor  the constructor
     * @param hStruct      the struct handle
     * @param ahArg        the arguments
     *
     * @return a FullyBoundHandle representing the finalizer
     */
    public static xFunction.FullyBoundHandle makeFinalizer(MethodStructure constructor,
                                          ObjectHandle hStruct, ObjectHandle[] ahArg)
        {
        MethodStructure methodFinally = constructor.getConstructFinally();

        return methodFinally == null ? xFunction.FullyBoundHandle.NO_OP :
                xFunction.makeHandle(methodFinally).bindAll(hStruct, ahArg);
        }


    // ----- hash.get() support -----

    // call "hash.get" method for the given const value, placing the result into the frame local
    // return R_EXCEPTION, R_NEXT or R_CALL
    public static int callHash(Frame frame, ObjectHandle hConst)
        {
        TypeComposition clzConst = hConst.getComposition();
        CallChain chain = clzConst.getPropertyGetterChain("hash");

        if (chain.isNative())
            {
            xConst template = (xConst) clzConst.getTemplate();
            return template.buildHashCode(frame, hConst, Frame.RET_LOCAL);
            }

        ObjectHandle[] ahVar = new ObjectHandle[chain.getTop().getMaxVars()];
        return clzConst.getTemplate().invoke1(frame, chain, hConst, ahVar, Frame.RET_LOCAL);
        }

    // ----- to<String> support -----

    // call "to<String>" method for the given value, placing the result into the frame local
    // return R_EXCEPTION, R_NEXT or R_CALL
    public static int callToString(Frame frame, ObjectHandle hValue)
        {
        TypeComposition clzValue = hValue.getComposition();
        CallChain chain = clzValue.getMethodCallChain(SIG_TO_STRING, Access.PUBLIC);

        if (chain.isNative())
            {
            return clzValue.getTemplate().buildStringValue(frame, hValue, Frame.RET_LOCAL);
            }

        ObjectHandle[] ahVar = new ObjectHandle[chain.getTop().getMaxVars()];
        return clzValue.getTemplate().invoke1(frame, chain, hValue, ahVar, Frame.RET_LOCAL);
        }


    // ----- "local property as an argument" support -----

    static public class GetArgument
                implements Frame.Continuation
        {
        public GetArgument(ObjectHandle[] ahTarget, Frame.Continuation continuation)
            {
            this.ahTarget = ahTarget;
            this.continuation = continuation;
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            ahTarget[0] = frameCaller.getFrameLocal();

            return continuation.proceed(frameCaller);
            }

        public int doNext(Frame frameCaller)
            {
            ObjectHandle handle = ahTarget[0];
            if (handle instanceof PropertyHandle)
                {
                ObjectHandle hThis = frameCaller.getThis();
                String sProp = ((PropertyHandle) handle).m_property.getName();

                switch (hThis.getTemplate().getPropertyValue(
                        frameCaller, hThis, sProp, Frame.RET_LOCAL))
                    {
                    case Op.R_NEXT:
                        // replace the property handle with the value
                        ahTarget[0] = frameCaller.getFrameLocal();
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }
            return continuation.proceed(frameCaller);
            }

        private final ObjectHandle[] ahTarget;
        private final Frame.Continuation continuation;
        }

    static public class GetArguments
                implements Frame.Continuation
        {
        public GetArguments(ObjectHandle[] ahVar, Frame.Continuation continuation)
            {
            this.ahVar = ahVar;
            this.continuation = continuation;
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            updateResult(frameCaller);

            return doNext(frameCaller);
            }

        protected void updateResult(Frame frameCaller)
            {
            // replace a property handle with the value
            ahVar[index] = frameCaller.getFrameLocal();
            }

        public int doNext(Frame frameCaller)
            {
            while (++index < ahVar.length)
                {
                ObjectHandle handle = ahVar[index];
                if (handle == null)
                    {
                    // nulls can only be at the tail of the array
                    break;
                    }

                if (handle instanceof PropertyHandle)
                    {
                    ObjectHandle hThis = frameCaller.getThis();
                    String sProp = ((PropertyHandle) handle).m_property.getName();

                    switch (hThis.getTemplate().getPropertyValue(
                            frameCaller, hThis, sProp, Frame.RET_LOCAL))
                        {
                        case Op.R_NEXT:
                            // replace the property handle with the value
                            updateResult(frameCaller);
                            break;

                        case Op.R_CALL:
                            frameCaller.m_frameNext.setContinuation(this);
                            return Op.R_CALL;

                        case Op.R_EXCEPTION:
                            return Op.R_EXCEPTION;

                        default:
                            throw new IllegalStateException();
                        }
                    }
                }
            return continuation.proceed(frameCaller);
            }

        private final ObjectHandle[] ahVar;
        private final Frame.Continuation continuation;
        private int index = -1;
        }

    static public class AssignValues
            implements Frame.Continuation
        {
        public AssignValues(int[] aiReturn, ObjectHandle[] ahValue)
            {
            this.aiReturn = aiReturn;
            this.ahValue = ahValue;
            }

        public int proceed(Frame frameCaller)
            {
            while (++index < aiReturn.length)
                {
                switch (frameCaller.assignValue(aiReturn[index], ahValue[index]))
                    {
                    case Op.R_BLOCK:
                        fBlock = true;
                        // fall through
                    case Op.R_NEXT:
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }

            return fBlock ? Op.R_BLOCK : Op.R_NEXT;
            }

        private final int[] aiReturn;
        private final ObjectHandle[] ahValue;

        private int index = -1;
        private boolean fBlock;
        }

    // ----- comparison support -----

    public static int callEqualsSequence(Frame frame, TypeConstant type1, TypeConstant type2,
                                         ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xBoolean.TRUE);
            }

        switch (type1.callEquals(frame, hValue1, hValue2, Frame.RET_LOCAL))
            {
            case Op.R_NEXT:
                return completeEquals(frame, type2, hValue1, hValue2, iReturn);

            case Op.R_CALL:
                frame.m_frameNext.setContinuation(frameCaller ->
                    completeEquals(frameCaller, type2, hValue1, hValue2, iReturn));
                return Op.R_CALL;

            case Op.R_EXCEPTION:
                return Op.R_EXCEPTION;

            default:
                throw new IllegalStateException();
            }
        }

    protected static int completeEquals(Frame frame, TypeConstant type2,
                                        ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        ObjectHandle hResult = frame.getFrameLocal();
        return hResult == xBoolean.FALSE
            ? frame.assignValue(iReturn, hResult)
            : type2.callEquals(frame, hValue1, hValue2, iReturn);
        }

    public static int callCompareSequence(Frame frame, TypeConstant type1, TypeConstant type2,
                                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xOrdered.EQUAL);
            }

        switch (type1.callCompare(frame, hValue1, hValue2, Frame.RET_LOCAL))
            {
            case Op.R_NEXT:
                return completeCompare(frame, type2, hValue1, hValue2, iReturn);

            case Op.R_CALL:
                frame.m_frameNext.setContinuation(frameCaller ->
                    completeCompare(frameCaller, type2, hValue1, hValue2, iReturn));
                return Op.R_CALL;

            case Op.R_EXCEPTION:
                return Op.R_EXCEPTION;

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * Completion of the callCompare implementation.
     */
    protected static int completeCompare(Frame frame, TypeConstant type2,
                                  ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        ObjectHandle hResult = frame.getFrameLocal();
        return hResult != xOrdered.EQUAL
            ? frame.assignValue(iReturn, hResult)
            : type2.callCompare(frame, hValue1, hValue2, iReturn);
        }


    // ----- toString support -----

    public static class ArrayToString
            implements Frame.Continuation
        {
        public ArrayToString(StringBuilder sb, ObjectHandle[] ahValue,
                             String[] asLabel, Frame.Continuation nextStep)
            {
            this.sb = sb;
            this.ahValue = ahValue;
            this.asLabel = asLabel;
            this.nextStep = nextStep;
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            if (updateResult(frameCaller))
                {
                return doNext(frameCaller);
                }

            // too much text; enough for an output...
            return nextStep.proceed(frameCaller);
            }

        // return false if the buffer is full
        protected boolean updateResult(Frame frameCaller)
            {
            StringHandle hString = (StringHandle) frameCaller.getFrameLocal();
            String sLabel = asLabel == null ? null : asLabel[index];

            if (sLabel != null)
                {
                sb.append(sLabel).append('=');
                }
            sb.append(hString.getValue());

            if (sb.length() < 1024*32)
                {
                sb.append(", ");
                return true;
                }

            sb.append("...");
            return false;
            }

        public int doNext(Frame frameCaller)
            {
            while (++index < ahValue.length)
                {
                switch (callToString(frameCaller, ahValue[index]))
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        continue;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }

            sb.setLength(sb.length() - 2); // remove the trailing ", "
            sb.append(')');

            return nextStep.proceed(frameCaller);
            }

        final private StringBuilder sb;
        final private ObjectHandle[] ahValue;
        final private String[] asLabel;
        final private Frame.Continuation nextStep;

        private int index = -1;
        }

    // ----- various run-time support -----

    /**
     * Create a pseudo frame that will wait on the specified future.
     *
     * @param frame     the caller frame
     * @param cfResult  the CompletableFuture to wait for
     * @param iReturn   the return register for the result
     *
     * @return a new frame
     */
    public static Frame createWaitFrame(Frame frame,
                                        CompletableFuture<ObjectHandle> cfResult, int iReturn)
        {
        ObjectHandle[] ahFuture = new ObjectHandle[]{xFutureVar.makeHandle(cfResult)};

        Frame frameNext = frame.createNativeFrame(GET_AND_RETURN, ahFuture, iReturn, null);

        frameNext.f_aInfo[0] = frame.new VarInfo(xFutureVar.TYPE, Frame.VAR_DYNAMIC_REF);

        return frameNext;
        }

    /**
     * Create a pseudo frame that will wait on multiple specified futures.
     *
     * @param frame     the caller frame
     * @param cfResult  the CompletableFuture to wait for
     * @param aiReturn  the return registers for the results
     *
     * @return a new frame
     */
    public static Frame createWaitFrame(Frame frame,
                                        CompletableFuture<ObjectHandle[]> cfResult, int[] aiReturn)
        {
        int cReturns = aiReturn.length;

        ObjectHandle[] ahFuture = new ObjectHandle[cReturns];
        Frame frameNext = frame.createNativeFrame(GET_AND_RETURN, ahFuture, Frame.RET_MULTI, aiReturn);

        // create a pseudo frame to deal with the multiple waits
        for (int i = 0; i < cReturns; i++)
            {
            int iResult = i;

            CompletableFuture<ObjectHandle> cfReturn =
                    cfResult.thenApply(ahResult -> ahResult[iResult]);

            ahFuture[i] = xFutureVar.makeHandle(cfReturn);
            frameNext.f_aInfo[i] = frame.new VarInfo(xFutureVar.TYPE, Frame.VAR_DYNAMIC_REF);
            }

        return frameNext;
        }

    private static final Op[] GET_AND_RETURN = new Op[]
        {
        new Op()
            {
            public int process(Frame frame, int iPC)
                {
                try
                    {
                    int cValues = frame.f_ahVar.length;

                    assert cValues > 0;

                    if (cValues == 1)
                        {
                        assert frame.f_aiReturn == null;

                        ObjectHandle hValue = frame.getArgument(0);
                        if (hValue == null)
                            {
                            return R_REPEAT;
                            }
                        return frame.returnValue(hValue);
                        }

                    assert frame.f_iReturn == Frame.RET_MULTI;

                    ObjectHandle[] ahValue = new ObjectHandle[cValues];
                    for (int i = 0; i < cValues; i++)
                        {
                        ObjectHandle hValue = frame.getArgument(i);
                        if (hValue == null)
                            {
                            return R_REPEAT;
                            }
                        ahValue[i] = hValue;
                        }

                    return frame.returnValues(ahValue);
                    }
                catch (ObjectHandle.ExceptionHandle.WrapperException e)
                    {
                    return frame.raiseException(e);
                    }
                }

            public String toString()
                {
                return "GetAndReturn";
                }
            }
        };

    /**
     * An abstract base for in-place operation support.
     */
    public static abstract class AbstractInPlace
            implements Frame.Continuation
        {
        protected ObjectHandle hValueOld;
        protected ObjectHandle hValueNew;
        protected int ixStep = -1;

        @Override
        public int proceed(Frame frameCaller)
            {
            updateResult(frameCaller);

            return doNext(frameCaller);
            }

        protected void updateResult(Frame frameCaller)
            {
            switch (ixStep)
                {
                case 0: // get
                    hValueOld = frameCaller.getFrameLocal();
                    break;

                case 1: // action
                    hValueNew = frameCaller.getFrameLocal();
                    break;

                case 2: // set
                    break;

                default:
                    throw new IllegalStateException();
                }
            }

        public abstract int doNext(Frame frameCaller);
        }

    /**
     * In place property unary operation support.
     */
    public static class InPlacePropertyUnary
            extends AbstractInPlace
        {
        private final UnaryAction action;
        private final ClassTemplate template;
        private final ObjectHandle hTarget;
        private final String sPropName;
        private final boolean fPost;
        private final int iReturn;

        protected InPlacePropertyUnary(UnaryAction action, ClassTemplate template,
                                       ObjectHandle hTarget, String sPropName, boolean fPost,
                                       int iReturn)
            {
            this.action = action;
            this.template = template;
            this.hTarget = hTarget;
            this.sPropName = sPropName;
            this.fPost = fPost;
            this.iReturn = iReturn;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = template.
                            getPropertyValue(frameCaller, hTarget, sPropName, Frame.RET_LOCAL);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld);
                        break;

                    case 2:
                        iResult = template.
                            setPropertyValue(frameCaller, hTarget, sPropName, hValueNew);
                        break;

                    case 3:
                        return frameCaller.assignValue(iReturn, fPost ? hValueOld : hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place property binary operation support.
     */
    public static class InPlacePropertyBinary
            extends AbstractInPlace
        {
        private final BinaryAction action;
        private final ClassTemplate template;
        private final ObjectHandle hTarget;
        private final String sPropName;
        private final ObjectHandle hArg;

        protected InPlacePropertyBinary(BinaryAction action, ClassTemplate template,
                                        ObjectHandle hTarget, String sPropName, ObjectHandle hArg)
            {
            this.action = action;
            this.template = template;
            this.hTarget = hTarget;
            this.sPropName = sPropName;
            this.hArg = hArg;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = template.getPropertyValue(frameCaller, hTarget, sPropName, Frame.RET_LOCAL);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld, hArg);
                        break;

                    case 2:
                        return template.setPropertyValue(frameCaller, hTarget, sPropName, hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place Var unary operation support.
     */
    public static class InPlaceVarUnary
            extends AbstractInPlace
        {
        private final UnaryAction action;
        private final RefHandle hTarget;
        private final boolean fPost;
        private final int iReturn;

        /**
         * @param action   the action
         * @param hTarget  the target Var
         * @param fPost    if true, the the operation is performed after the current value is returned
 *                 (e.g. i--); otherwise - before that (e.g. --i)
         * @param iReturn  the register to place the result of the operation into
         */
        public InPlaceVarUnary(UnaryAction action, RefHandle hTarget, boolean fPost, int iReturn)
            {
            this.action = action;
            this.hTarget = hTarget;
            this.fPost = fPost;
            this.iReturn = iReturn;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int nStep = ++ixStep;

                int iResult;
                switch (nStep)
                    {
                    case 0:
                        iResult = hTarget.getVarSupport().get(frameCaller, hTarget, Frame.RET_LOCAL);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld);
                        break;

                    case 2:
                        iResult = hTarget.getVarSupport().set(frameCaller, hTarget, hValueNew);
                        break;

                    case 3:
                        return frameCaller.assignValue(iReturn, fPost ? hValueOld : hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place Var binary operation support.
     */
    public static class InPlaceVarBinary
            extends AbstractInPlace
        {
        private final BinaryAction action;
        private final RefHandle hTarget;
        private final ObjectHandle hArg;

        public InPlaceVarBinary(BinaryAction action, RefHandle hTarget, ObjectHandle hArg)
            {
            this.action = action;
            this.hTarget = hTarget;
            this.hArg = hArg;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = hTarget.getVarSupport().get(frameCaller, hTarget, Frame.RET_LOCAL);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld, hArg);
                        break;

                    case 2:
                        return hTarget.getVarSupport().set(frameCaller, hTarget, hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.setContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    // the lambda for unary actions
    public interface UnaryAction
        {
        // invoke and place the result into Frame.RET_LOCAL
        int invoke(Frame frame, ObjectHandle hTarget);

        // ----- action constants ------------------------------------------------------------------

        UnaryAction INC = (frameCaller, hValue) ->
            hValue.getOpSupport().invokeNext(frameCaller, hValue, Frame.RET_LOCAL);

        UnaryAction DEC = (frameCaller, hValue) ->
            hValue.getOpSupport().invokePrev(frameCaller, hValue, Frame.RET_LOCAL);

        UnaryAction NEG = (frameCaller, hValue) ->
            hValue.getOpSupport().invokeNeg(frameCaller, hValue, Frame.RET_LOCAL);
        }

    // the lambda for binary actions
    public interface BinaryAction
        {
        // invoke and place the result into Frame.RET_LOCAL
        int invoke(Frame frame, ObjectHandle hTarget, ObjectHandle hArg);

        // ----- action constants ------------------------------------------------------------------

        BinaryAction ADD = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeAdd(frameCaller, hValue, hArg, Frame.RET_LOCAL);

        BinaryAction SUB = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeSub(frameCaller, hValue, hArg, Frame.RET_LOCAL);

        BinaryAction MUL = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeMul(frameCaller, hValue, hArg, Frame.RET_LOCAL);

        BinaryAction DIV = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeDiv(frameCaller, hValue, hArg, Frame.RET_LOCAL);

        BinaryAction MOD = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeMod(frameCaller, hValue, hArg, Frame.RET_LOCAL);
        }
    }
