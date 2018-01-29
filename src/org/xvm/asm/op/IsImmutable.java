package org.xvm.asm.op;


import java.io.DataInput;
import java.io.IOException;

import org.xvm.asm.Constant;
import org.xvm.asm.OpTest;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.template.xBoolean;


/**
 * IS_IMMUT rvalue, lvalue-return ; (T is immutable) -> Boolean
 */
public class IsImmutable
        extends OpTest
    {
    /**
     * Construct an IS_IMMUT op based on the specified arguments.
     *
     * @param arg        the value Argument
     * @param argReturn  the location to store the Boolean result
     */
     public IsImmutable(Argument arg, Argument argReturn)
        {
        super(arg, argReturn);
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public IsImmutable(DataInput in, Constant[] aconst)
            throws IOException
        {
        super(in, aconst);
        }

    @Override
    public int getOpCode()
        {
        return OP_IS_IMMT;
        }

    @Override
    protected int completeUnaryOp(Frame frame, ObjectHandle hValue)
        {
        return frame.assignValue(m_nRetValue, xBoolean.makeHandle(hValue.isMutable()));
        }
    }
