package org.xvm.asm.op;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.Argument;
import org.xvm.asm.Constant;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.OpInvocable;

import org.xvm.asm.constants.MethodConstant;

import org.xvm.runtime.CallChain;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.Utils;

import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * NVOK_NT rvalue-target, CONST-METHOD, #params:(rvalue), lvalue-return-tuple
 */
public class Invoke_NT
        extends OpInvocable
    {
    /**
     * Construct an NVOK_NT op based on the passed arguments.
     *
     * @param argTarget    the target Argument
     * @param constMethod  the method constant
     * @param aArgValue    the array of Argument values
     * @param argReturn    the Argument to move the result into
     */
    public Invoke_NT(Argument argTarget, MethodConstant constMethod, Argument[] aArgValue, Argument argReturn)
        {
        super(argTarget, constMethod);

        m_aArgValue = aArgValue;
        m_argReturn = argReturn;
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public Invoke_NT(DataInput in, Constant[] aconst)
            throws IOException
        {
        super(in, aconst);

        m_anArgValue = readIntArray(in);
        m_nRetValue = readPackedInt(in);
        }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
            throws IOException
        {
        super.write(out, registry);

        if (m_aArgValue != null)
            {
            m_anArgValue = encodeArguments(m_aArgValue, registry);
            m_nRetValue = encodeArgument(m_argReturn, registry);
            }

        writeIntArray(out, m_anArgValue);
        writePackedLong(out, m_nRetValue);
        }

    @Override
    public int getOpCode()
        {
        return OP_NVOK_NT;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        try
            {
            ObjectHandle hTarget = frame.getArgument(m_nTarget);
            if (hTarget == null)
                {
                return R_REPEAT;
                }

            if (isDeferred(hTarget))
                {
                ObjectHandle[] ahArg = frame.getArguments(m_anArgValue, m_anArgValue.length);
                if (ahArg == null)
                    {
                    return R_REPEAT;
                    }

                ObjectHandle[] ahTarget = new ObjectHandle[] {hTarget};
                Frame.Continuation stepNext = frameCaller -> resolveArgs(frameCaller, ahTarget[0], ahArg);

                return new Utils.GetArguments(ahTarget, stepNext).doNext(frame);
                }

            return resolveArgs(frame, hTarget, null);
            }
        catch (ExceptionHandle.WrapperException e)
            {
            return frame.raiseException(e);
            }
        }

    protected int resolveArgs(Frame frame, ObjectHandle hTarget, ObjectHandle[] ahArg)
        {
        CallChain chain = getCallChain(frame, hTarget);
        MethodStructure method = chain.getTop();

        checkReturnTupleRegister(frame, hTarget);

        ObjectHandle[] ahVar;
        if (ahArg == null)
            {
            try
                {
                ahVar = frame.getArguments(m_anArgValue, method.getMaxVars());
                if (ahVar == null)
                    {
                    if (m_nTarget == A_STACK)
                        {
                        frame.pushStack(hTarget);
                        }
                    return R_REPEAT;
                    }
                }
            catch (ExceptionHandle.WrapperException e)
                {
                return frame.raiseException(e);
                }
            }
        else
            {
            ahVar = Utils.ensureSize(ahArg, method.getMaxVars());
            }

        if (anyDeferred(ahVar))
            {
            Frame.Continuation stepNext = frameCaller ->
                complete(frameCaller, chain, hTarget, ahVar);
            return new Utils.GetArguments(ahVar, stepNext).doNext(frame);
            }
        return complete(frame, chain, hTarget, ahVar);
        }

    protected int complete(Frame frame, CallChain chain, ObjectHandle hTarget, ObjectHandle[] ahVar)
        {
        return chain.isNative()
             ? hTarget.getTemplate().invokeNativeT(frame, chain.getTop(), hTarget, ahVar, m_nRetValue)
             : hTarget.getTemplate().invokeT(frame, chain, hTarget, ahVar, m_nRetValue);
        }

    @Override
    public void registerConstants(ConstantRegistry registry)
        {
        super.registerConstants(registry);

        registerArguments(m_aArgValue, registry);
        }

    @Override
    protected String getParamsString()
        {
        return getParamsString(m_anArgValue, m_aArgValue);
        }

    private int[] m_anArgValue;

    private Argument[] m_aArgValue;
    }
