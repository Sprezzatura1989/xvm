package org.xvm.proto.op;

import org.xvm.proto.Frame;
import org.xvm.proto.Frame.Guard;
import org.xvm.proto.Op;

/**
 * GUARD #handlers:(CONST_CLASS, rel_addr)  ; ENTER
 *
 * @author gg 2017.03.08
 */
public class GuardStart extends Op
    {
    private final int[] f_anClassConstId;
    private final int[] f_anCatchRelAddress;

    private Guard m_guard; // cached struct

    public GuardStart(int nClassConstId, int nCatchAddress)
        {
        f_anClassConstId = new int[] {nClassConstId};
        f_anCatchRelAddress = new int[] {nCatchAddress};
        }

    public GuardStart(int[] anClassConstId, int[] anCatch)
        {
        assert anClassConstId.length == anCatch.length;

        f_anClassConstId = anClassConstId;
        f_anCatchRelAddress = anCatch;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        // ++ Enter
        int iScope = ++frame.f_aiRegister[I_SCOPE];

        frame.f_anNextVar[iScope] = frame.f_anNextVar[iScope-1];
        // --

        int iGuard = ++frame.f_aiRegister[I_GUARD];

        Guard[] aGuard = frame.m_aGuard;
        if (aGuard == null)
            {
            aGuard = frame.m_aGuard = new Frame.Guard[frame.f_function.m_cScopes];
            }

        Guard guard = m_guard;
        if (guard == null)
            {
            guard = m_guard = new Guard(iPC, iScope, f_anClassConstId, f_anCatchRelAddress);
            }
        aGuard[iGuard] = guard;

        return iPC + 1;
        }
    }
