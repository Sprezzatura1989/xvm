package org.xvm.asm.op;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.xvm.asm.Argument;
import org.xvm.asm.Constant;
import org.xvm.asm.Op;
import org.xvm.asm.Register;

import org.xvm.asm.constants.ArrayConstant;
import org.xvm.asm.constants.MatchAnyConstant;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.DeferredCallHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.ObjectHandle.GenericHandle;
import org.xvm.runtime.ObjectHeap;
import org.xvm.runtime.Utils;

import static org.xvm.util.Handy.readMagnitude;
import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * JMP_VAL_N #:(rvalue), #:(CONST, addr), addr-default ; if value equals a constant, jump to address, otherwise default
 * <ul>
 *     <li>with support for wildcard field matches (using MatchAnyConstant)</li>
 *     <li>with support for interval matches (using IntervalConstant)</li>
 * </ul>
 */
public class JumpVal_N
        extends OpSwitch
    {
    /**
     * Construct a JMP_VAL_N op.
     *
     * @param aArgVal     an array of value Arguments (the "condition")
     * @param aConstCase  an array of "case" values (constants)
     * @param aOpCase     an array of Ops to jump to
     * @param opDefault   an Op to jump to in the "default" case
     */
    public JumpVal_N(Argument[] aArgVal, Constant[] aConstCase, Op[] aOpCase, Op opDefault)
        {
        super(aConstCase, aOpCase, opDefault);

        m_aArgCond = aArgVal;
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public JumpVal_N(DataInput in, Constant[] aconst)
            throws IOException
        {
        super(in, aconst);

        int   cArgs = readMagnitude(in);
        int[] anArg = new int[cArgs];
        for (int i = 0; i < cArgs; ++i)
            {
            anArg[i] = readPackedInt(in);
            }
        m_anArgCond = anArg;
        }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
            throws IOException
        {
        super.write(out, registry);

        if (m_aArgCond != null)
            {
            m_anArgCond = encodeArguments(m_aArgCond, registry);
            }

        int[] anArg = m_anArgCond;
        int   cArgs = anArg.length;
        writePackedLong(out, cArgs);
        for (int i = 0; i < cArgs; ++i)
            {
            writePackedLong(out, anArg[i]);
            }
        }

    @Override
    public int getOpCode()
        {
        return OP_JMP_VAL_N;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        try
            {
            ObjectHandle[] ahValue = frame.getArguments(m_anArgCond, m_anArgCond.length);
            if (ahValue == null)
                {
                return R_REPEAT;
                }

            if (anyDeferred(ahValue))
                {
                Frame.Continuation stepNext = frameCaller ->
                        collectCaseConstants(frame, iPC, ahValue);

                return new Utils.GetArguments(ahValue, stepNext).doNext(frame);
                }

            return collectCaseConstants(frame, iPC, ahValue);
            }
        catch (ExceptionHandle.WrapperException e)
            {
            return frame.raiseException(e);
            }
        }

    protected int collectCaseConstants(Frame frame, int iPC, ObjectHandle[] ahValue)
        {
        if (m_aahCases == null)
            {
            m_aahCases = new ObjectHandle[m_aofCase.length][];

            return explodeConstants(frame, iPC, ahValue, 0);
            }
        return complete(frame, iPC, ahValue);
        }

    protected int explodeConstants(Frame frame, int iPC, ObjectHandle[] ahValue, int iRow)
        {
        for (int cRows = m_aofCase.length; iRow < cRows; iRow++)
            {
            int            cColumns     = ahValue.length;
            ArrayConstant  contValues   = (ArrayConstant) frame.getConstant(m_anConstCase[iRow]);
            Constant[]     aconstValues = contValues.getValue();
            ObjectHandle[] ahCases      = new ObjectHandle[cColumns];

            m_aahCases[iRow] = ahCases;

            assert aconstValues.length == cColumns;

            boolean fDeferred = false;
            for (int iC = 0; iC < cColumns; iC++)
                {
                Constant constCase = aconstValues[iC];
                if (constCase instanceof MatchAnyConstant)
                    {
                    ahCases[iC] = ObjectHandle.DEFAULT;
                    continue;
                    }

                ObjectHandle hCase = ahCases[iC] = frame.getConstHandle(constCase);
                if (hCase instanceof DeferredCallHandle)
                    {
                    fDeferred = true;
                    }
                }

            if (fDeferred)
                {
                final int iRowNext = iRow + 1;
                Frame.Continuation stepNext =
                    frameCaller -> explodeConstants(frame, iPC, ahValue, iRowNext);
                return new Utils.GetArguments(ahCases, stepNext).doNext(frame);
                }
            }

        if (m_aofCase.length < 64)
            {
            buildSmallJumpMaps(ahValue);
            }
        else
            {
            buildLargeJumpMaps(ahValue);
            }
        return complete(frame, iPC, ahValue);
        }

    protected int complete(Frame frame, int iPC, ObjectHandle[] ahValue)
        {
        return m_aofCase.length < 64
                ? findSmall(frame, iPC, ahValue)
                : findLarge(frame, iPC, ahValue);
        }

    protected int findSmall(Frame frame, int iPC, ObjectHandle[] ahValue)
        {
        Algorithm[]               aAlg   = m_aAlgorithm;
        Map<ObjectHandle, Long>[] aMap   = m_amapJumpSmall;
        long[]                    alWild = m_alWildcardSmall;
        long                      ixBits = -1;

        // first go over the native columns
        for (int iC = 0, cCols = ahValue.length; iC < cCols; iC++)
            {
            ObjectHandle hValue   = ahValue[iC];
            long         ixColumn = 0; // matching cases in this column
            switch (aAlg[iC])
                {
                case NativeInterval:
                    {
                    List<Object[]> listInterval = m_alistIntervalSmall[iC];
                    for (int iR = 0, cR = listInterval.size(); iR < cR; iR++)
                        {
                        Object[] ao = listInterval.get(iR);

                        long lBits = (Long) ao[2];

                        // we only need to compare the range if there is a chance that it can impact
                        // the result
                        if ((lBits & ixBits) != 0)
                            {
                            ObjectHandle hLow  = (ObjectHandle) ao[0];
                            ObjectHandle hHigh = (ObjectHandle) ao[1];

                            if (hValue.compareTo(hLow) >= 0 && hValue.compareTo(hHigh) <= 0)
                                {
                                ixColumn |= lBits;
                                }
                            }
                        }
                    // fall through and process the exact match
                    }

                case NativeSimple:
                    {
                    Long LBits = aMap[iC].get(hValue);
                    if (LBits != null)
                        {
                        ixColumn |= LBits.longValue();
                        }
                    break;
                    }

                default:
                    continue;
                }

            // ixWild[i] == 0 means "no wildcards in column i"
            ixColumn |= alWild[iC];
            ixBits   &= ixColumn;
            if (ixBits == 0)
                {
                // no match
                return iPC + m_ofDefault;
                }
            }

        if (m_algorithm.isNative())
            {
            long lBit = Long.lowestOneBit(ixBits);
            return iPC + m_aofCase[Long.numberOfTrailingZeros(lBit)];
            }

        return findSmallNatural(frame, iPC, ahValue, ixBits);
        }

    protected int findSmallNatural(Frame frame, int iPC, ObjectHandle[] ahValue, long ixBits)
        {
        throw new UnsupportedOperationException();
        }

    protected int findLarge(Frame frame, int iPC, ObjectHandle[] ahValue)
        {
        throw new UnsupportedOperationException();
        }

    private void buildSmallJumpMaps(ObjectHandle[] ahValue)
        {
        int[] anConstCase = m_anConstCase;
        int   cRows       = anConstCase.length;
        int   cColumns    = ahValue.length;

        Map<ObjectHandle, Long>[] amapJump  = new Map[cColumns];

        m_amapJumpSmall   = amapJump;
        m_alWildcardSmall = new long[cColumns];
        m_aAlgorithm      = new Algorithm[cColumns];
        m_algorithm       = Algorithm.NativeSimple;

        // first check for native vs. natural comparison
        for (int iC = 0; iC < cColumns; iC++)
            {
            amapJump[iC] = new HashMap<>(cRows);
            if (ahValue[iC].isNativeEqual())
                {
                m_aAlgorithm[iC] = Algorithm.NativeSimple;
                }
            else
                {
                m_aAlgorithm[iC] = Algorithm.NaturalSimple;
                }
            }

        for (int iC = 0; iC < cColumns; iC++)
            {
            amapJump[iC] = new HashMap<>(cRows);
            }

        // now check for presence of ranges among the rows (cases)
        for (int iR = 0; iR < cRows; iR++ )
            {
            long lCaseBit = 1 << iR;
            for (int iC = 0; iC < cColumns; iC++)
                {
                ObjectHandle hCase = m_aahCases[iR][iC];

                if (hCase == ObjectHandle.DEFAULT)
                    {
                    m_alWildcardSmall[iC] |= lCaseBit;
                    continue;
                    }

                assert !hCase.isMutable();

                if (m_aAlgorithm[iC].isNative())
                    {
                    if (hCase.isNativeEqual())
                        {
                        amapJump[iC].compute(hCase, (h, LOld) ->
                            Long.valueOf(lCaseBit | (LOld == null ?  0 : LOld.longValue())));
                        }
                    else
                        {
                        // this must be an interval of native values
                        m_aAlgorithm[iC] = Algorithm.NativeInterval;

                        addInterval((GenericHandle) hCase, lCaseBit, cColumns, iC);
                        }
                    }
                else // natural comparison
                    {
                    if (hCase.getType().isAssignableTo(ahValue[iC].getType()))
                        {
                        amapJump[iC].compute(hCase, (h, LOld) ->
                            Long.valueOf(lCaseBit | (LOld == null ?  0 : LOld.longValue())));
                        }
                    else
                        {
                        // this must be an interval of native values
                        m_aAlgorithm[iC] = Algorithm.NaturalInterval;

                        addInterval((GenericHandle) hCase, lCaseBit, cColumns, iC);
                        }
                    }
                m_algorithm = m_algorithm.worstOf(m_aAlgorithm[iC]);
                }
            }
        }

    /**
     * Add an interval definition for the specified column.
     *
     * @param hInterval the Interval value
     * @param lCaseBit  the case index bit
     * @param cColumns  the total number of columns
     * @param iC        the current column to add an interval to
     */
    private void addInterval(GenericHandle hInterval, long lCaseBit, int cColumns, int iC)
        {
        ObjectHandle hLow  = hInterval.getField("lowerBound");
        ObjectHandle hHigh = hInterval.getField("upperBound");

        // TODO: if the interval is small, replace it with the exact hits for native values
        ensureIntervalList(cColumns, iC).add(
                new Object[]{hLow, hHigh, Long.valueOf(lCaseBit)});
        }

    private List<Object[]> ensureIntervalList(int cColumns, int iCol)
        {
        List<Object[]>[] alist = m_alistIntervalSmall;
        if (alist == null)
            {
            alist = m_alistIntervalSmall = new List[cColumns];
            }
        List<Object[]> list = alist[iCol];
        if (list == null)
            {
            list = alist[iCol] = new ArrayList<>();
            }
        return list;
        }

    private void buildLargeJumpMaps(ObjectHandle[] ahValue)
        {
        throw new UnsupportedOperationException();
        }

    @Override
    public void registerConstants(ConstantRegistry registry)
        {
        registerArguments(m_aArgCond, registry);

        super.registerConstants(registry);
        }

    @Override
    protected void appendArgDescription(StringBuilder sb)
        {
        int cArgConds  = m_aArgCond  == null ? 0 : m_aArgCond.length;
        int cNArgConds = m_anArgCond == null ? 0 : m_anArgCond.length;
        int cArgs      = Math.max(cArgConds, cNArgConds);

        for (int i = 0; i < cArgs; ++i)
            {
            Argument arg  = i < cArgConds  ? m_aArgCond [i] : null;
            int      nArg = i < cNArgConds ? m_anArgCond[i] : Register.UNKNOWN;
            sb.append(Argument.toIdString(arg, nArg))
                    .append(", ");
            }
        }


    // ----- fields --------------------------------------------------------------------------------

    protected int[]      m_anArgCond;
    private   Argument[] m_aArgCond;

    /**
     * Cached array of ObjectHandles for cases. First index is a row; second is a column.
     */
    private transient ObjectHandle[][] m_aahCases;

    /**
     * Cached array of jump maps for # cases < 64. The Long represents a bitset of matching cases.
     * The bits are 0-based (bit 0 representing case #0), therefore the value of 0 is invalid.
     */
    private transient Map<ObjectHandle, Long>[] m_amapJumpSmall;
    /**
     * The bitmask of wildcard cases per column.
     * The bits are 0-based (bit 0 representing case #0), therefore the value of 0L indicates an
     * absence of wildcards in the column.
     */
    private transient long[] m_alWildcardSmall;
    /**
     * A list of intervals per column;
     *  a[0] - lower bound (ObjectHandle);
     *  a[1] - upper bound (ObjectHandle);
     *  a[2] - the case mask (Long)
     */
    private transient List<Object[]>[] m_alistIntervalSmall;

    // cached array of jump maps; for # cases >= 64
    private transient Map<ObjectHandle, BitSet>[] m_amapJumpLarge; // maps per column keyed by constant handle
    private transient BitSet[] m_lDefaultLarge; // bitmask of default cases per column

    private transient Algorithm[] m_aAlgorithm; // algorithm per column
    private transient Algorithm   m_algorithm;  // the "worst" of the column algorithms
    }
