package org.xvm.proto.op;

import org.xvm.proto.Frame;
import org.xvm.proto.Op;

/**
 * RETURN_1 rvalue
 *
 * @author gg 2017.03.08
 */
public class Return_1 extends Op
    {
    private final int f_nArgValue;

    public Return_1(int nValue)
        {
        f_nArgValue = nValue;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        int iArg = f_nArgValue;

        frame.f_ahReturn[0] =
                iArg >= 0 ?
                    frame.f_ahVar[f_nArgValue] :
                iArg < -Op.MAX_CONST_ID ?
                    frame.getPredefinedArgument(iArg) :
                    frame.f_context.f_heapGlobal.ensureConstHandle(-iArg);
        return RETURN_NORMAL;
        }
    }
