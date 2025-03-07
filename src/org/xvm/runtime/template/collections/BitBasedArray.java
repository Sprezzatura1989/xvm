package org.xvm.runtime.template.collections;


import java.util.Arrays;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Op;

import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ArrayHandle;
import org.xvm.runtime.ObjectHandle.JavaLong;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.TypeComposition;

import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xException;
import org.xvm.runtime.template.xString;


/**
 * A base class for native Array implementations based on bit arrays.
 */
public abstract class BitBasedArray
        extends xArray
    {
    public static BitBasedArray INSTANCE;

    protected BitBasedArray(TemplateRegistry templates, ClassStructure structure)
        {
        super(templates, structure, false);
        }

    @Override
    public void initDeclared()
        {
        }

    @Override
    public ArrayHandle createArrayHandle(ClassComposition clzArray, ObjectHandle[] ahArg)
        {
        int    cBits = ahArg.length;
        byte[] ab    = new byte[storage(cBits)];
        for (int i = 0; i < cBits; i++)
            {
            if (((JavaLong) ahArg[i]).getValue() == 1)
                {
                ab[index(i)] |= bitMask(i);
                }
            }
        return new BitArrayHandle(clzArray, ab, cBits, Mutability.Constant);
        }

    @Override
    public ArrayHandle createArrayHandle(ClassComposition clzArray, int cCapacity, Mutability mutability)
        {
        return new BitArrayHandle(clzArray, 0, cCapacity, mutability);
        }

    @Override
    protected ArrayHandle createCopy(ArrayHandle hArray, Mutability mutability)
        {
        BitArrayHandle hSrc  = (BitArrayHandle) hArray;
        int            cBits = hSrc.m_cSize;

        return new BitArrayHandle(hSrc.getComposition(),
            Arrays.copyOfRange(hSrc.m_abValue, 0, storage(cBits)), cBits, mutability);
        }

    @Override
    protected void fill(ArrayHandle hArray, int cSize, ObjectHandle hValue)
        {
        assert cSize > 0;

        BitArrayHandle hba = (BitArrayHandle) hArray;
        byte[]         ab  = hba.m_abValue;

        if (isSet(hValue))
            {
            int ix = index(cSize - 1);
            if (ix > 0)
                {
                Arrays.fill(ab, 0, ix - 1, (byte) 1);
                }

            ab[ix] = tailMask(cSize - 1);

            if (ix + 1 < ab.length)
                {
                Arrays.fill(ab, ix + 1, ab.length - 1, (byte) 0);
                }
            }
        else
            {
            Arrays.fill(ab, 0, ab.length - 1, (byte) 0);
            }

        hba.m_cSize = cSize;
        }

    @Override
    public int extractArrayValue(Frame frame, ObjectHandle hTarget, long lIndex, int iReturn)
        {
        BitArrayHandle hArray = (BitArrayHandle) hTarget;

        if (lIndex < 0 || lIndex >= hArray.m_cSize)
            {
            return frame.raiseException(xException.outOfBounds(frame, lIndex, hArray.m_cSize));
            }
        return frame.assignValue(iReturn, makeBitHandle(getBit(hArray.m_abValue, (int) lIndex)));
        }

    @Override
    public int assignArrayValue(Frame frame, ObjectHandle hTarget, long lIndex, ObjectHandle hValue)
        {
        BitArrayHandle hArray = (BitArrayHandle) hTarget;

        int cSize = hArray.m_cSize;

        if (lIndex < 0 || lIndex > cSize)
            {
            return frame.raiseException(xException.outOfBounds(frame, lIndex, cSize));
            }

        switch (hArray.m_mutability)
            {
            case Constant:
                return frame.raiseException(xException.immutableObject(frame));

            case Persistent:
                return frame.raiseException(xException.unsupportedOperation(frame));
            }

        byte[] abValue = hArray.m_abValue;
        if (lIndex == cSize)
            {
            if (hArray.m_mutability == Mutability.FixedSize)
                {
                return frame.raiseException(xException.readOnly(frame));
                }

            // an array can only grow without any "holes"
            if (storage(cSize) == abValue.length)
                {
                abValue = hArray.m_abValue = grow(abValue, storage(cSize) + 1);
                }

            hArray.m_cSize++;
            }

        setBit(abValue, (int) lIndex, isSet(hValue));
        return Op.R_NEXT;
        }

    @Override
    public int callEquals(Frame frame, ClassComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        BitArrayHandle h1 = (BitArrayHandle) hValue1;
        BitArrayHandle h2 = (BitArrayHandle) hValue2;

        return frame.assignValue(iReturn,
                xBoolean.makeHandle(Arrays.equals(h1.m_abValue, h2.m_abValue)));
        }

    @Override
    public boolean compareIdentity(ObjectHandle hValue1, ObjectHandle hValue2)
        {
        BitArrayHandle hArray1 = (BitArrayHandle) hValue1;
        BitArrayHandle hArray2 = (BitArrayHandle) hValue2;

        if (hArray1.isMutable() || hArray2.isMutable() || hArray1.m_cSize != hArray2.m_cSize)
            {
            return false;
            }

        return Arrays.equals(hArray1.m_abValue, hArray2.m_abValue);
        }

    @Override
    protected int addElement(Frame frame, ObjectHandle hTarget, ObjectHandle hValue, int iReturn)
        {
        BitArrayHandle hArray = (BitArrayHandle) hTarget;
        int            ixNext = hArray.m_cSize;

        switch (hArray.m_mutability)
            {
            case Constant:
                return frame.raiseException(xException.immutableObject(frame));

            case FixedSize:
                return frame.raiseException(xException.readOnly(frame));

            case Persistent:
                // TODO: implement
                return frame.raiseException(xException.unsupportedOperation(frame));
            }

        byte[] abValue = hArray.m_abValue;
        if (ixNext == abValue.length)
            {
            abValue = hArray.m_abValue = grow(hArray.m_abValue, ixNext + 1);
            }
        hArray.m_cSize++;

        setBit(abValue, ixNext, isSet(hValue));
        return frame.assignValue(iReturn, hArray);
        }

    @Override
    protected int addElements(Frame frame, ObjectHandle hTarget, ObjectHandle hValue, int iReturn)
        {
        BitArrayHandle hArray = (BitArrayHandle) hTarget;

        switch (hArray.m_mutability)
            {
            case Constant:
                return frame.raiseException(xException.immutableObject(frame));

            case FixedSize:
                return frame.raiseException(xException.readOnly(frame));

            case Persistent:
                // TODO: implement
                return frame.raiseException(xException.unsupportedOperation(frame));
            }

        BitArrayHandle hArrayAdd = (BitArrayHandle) hValue;

        int cAdd = hArrayAdd.m_cSize;
        if (cAdd > 0)
            {
            byte[] abThis = hArray.m_abValue;
            int    cThis  = hArray.m_cSize;
            int    cNew   = cThis + cAdd;
            if (storage(cNew) > abThis.length)
                {
                abThis = hArray.m_abValue = grow(abThis, storage(cNew));
                }
            hArray.m_cSize = cNew;

            byte[] abAdd = hArrayAdd.m_abValue;
            for (int iBit = 0; iBit < cAdd; iBit++)
                {
                setBit(abThis, cThis + iBit, getBit(abAdd, iBit));
                }
            }
        return frame.assignValue(iReturn, hArray);
        }

    @Override
    protected int slice(Frame frame, ObjectHandle hTarget, long ixFrom, long ixTo, int iReturn)
        {
        BitArrayHandle hArray = (BitArrayHandle) hTarget;

        byte[] abValue = hArray.m_abValue;
        try
            {
            int    cBits = (int) (ixTo - ixFrom + 1);
            byte[] abNew = new byte[storage(cBits)];

            for (int iBit = 0; iBit <= cBits; iBit++)
                {
                setBit(abNew, iBit, getBit(abValue, (int) ixFrom + iBit));
                }

            BitArrayHandle hArrayNew = new BitArrayHandle(hTarget.getComposition(),
                abNew, cBits, Mutability.Mutable);

            return frame.assignValue(iReturn, hArrayNew);
            }
        catch (ArrayIndexOutOfBoundsException e)
            {
            long c = abValue.length;
            return frame.raiseException(
                xException.outOfBounds(frame, ixFrom < 0 || ixFrom >= c ? ixFrom : ixTo, c));
            }
        }

    /**
     * @return true iff the specified value represents a "set" bit
     */
    protected abstract boolean isSet(ObjectHandle hValue);

    /**
     * @return an ObjectHandle representing the bit value
     */
    protected abstract ObjectHandle makeBitHandle(boolean f);


    // ----- helper methods -----

    private byte[] grow(byte[] abValue, int cSize)
        {
        int cCapacity = calculateCapacity(abValue.length, cSize);

        byte[] abNew = new byte[cCapacity];
        System.arraycopy(abValue, 0, abNew, 0, abValue.length);
        return abNew;
        }

    /**
     * Calculate the size of a byte array to represent a bit array.
     *
     * @param cBits  the bit array size
     *
     * @return the byte array size
     */
    public static int storage(int cBits)
        {
        return (cBits - 1) / 8 + 1;
        }

    /**
     * Get a bit in the specified array of bytes.
     *
     * @param abValue  the byte array
     * @param iIndex   the bit index
     *
     * @return true iff the bit is set
     */
    public static boolean getBit(byte[] abValue, int iIndex)
        {
        return (abValue[index(iIndex)] & bitMask(iIndex)) != 0;
        }

    /**
     * Set or clear a bit in the specified array of bytes.
     *
     * @param abValue  the byte array
     * @param iIndex   the bit index
     * @param fSet     true if the bit is to be set; false for clear
     */
    public static void setBit(byte[] abValue, int iIndex, boolean fSet)
        {
        if (fSet)
            {
            abValue[index(iIndex)] |= bitMask(iIndex);
            }
        else
            {
            abValue[index(iIndex)] &= ~bitMask(iIndex);
            }
        }

    /**
     * Calculate an index of the specified bit in the byte array.
     *
     * @param iBit  the bit index
     *
     * @return the byte index
     */
    public static int index(int iBit)
        {
        return iBit / 8;
        }

    /**
     * Calculate a mask of the specified bit in the byte array at {@ling #index}.
     *
     * @param iBit  the bit index
     *
     * @return the mask
     */
    public static int bitMask(int iBit)
        {
        return 1 << iBit % 8;
        }

    /**
     * Calculate a tail mask for the specified bit in the byte array at {@ling #index}.
     *
     * @param iBit  the bit index
     *
     * @return the tail mask (all zeros pass the bit's position)
     */
    public static byte tailMask(int iBit)
        {
        return (byte) (0xFF >> (8 - iBit % 8));
        }


    // ----- handle --------------------------------------------------------------------------------

    public static class BitArrayHandle
            extends ArrayHandle
        {
        public byte[] m_abValue;

        public BitArrayHandle(TypeComposition clzArray, byte[] abValue, int cBits, Mutability mutability)
            {
            super(clzArray, mutability);

            m_abValue = abValue;
            m_cSize   = cBits;
            }

        protected BitArrayHandle(TypeComposition clzArray, int cBits, int cCapacity, Mutability mutability)
            {
            super(clzArray, mutability);

            m_abValue = new byte[storage(cCapacity)];
            }

        @Override
        public int getCapacity()
            {
            return m_abValue.length;
            }

        @Override
        public void makeImmutable()
            {
            if (isMutable())
                {
                // purge the unused space
                byte[] ab = m_abValue;
                int    c  = storage(m_cSize);
                if (ab.length != c)
                    {
                    byte[] abNew = new byte[c];
                    System.arraycopy(ab, 0, abNew, 0, c);
                    m_abValue = abNew;
                    }
                super.makeImmutable();
                }
            }

        @Override
        public boolean isNativeEqual()
            {
            return true;
            }

        @Override
        public int compareTo(ObjectHandle that)
            {
            byte[] abThis = m_abValue;
            int    cThis  = m_cSize;
            byte[] abThat = ((BitArrayHandle) that).m_abValue;
            int    cThat  = ((BitArrayHandle) that).m_cSize;

            if (cThis != cThat)
                {
                return cThis - cThat;
                }

            for (int i = 0, c = storage(cThis); i < c; i++)
                {
                int iDiff = abThis[i] - abThat[i];
                if (iDiff != 0)
                    {
                    return iDiff < 0 ? -1 : 1;
                    }
                }
            return 0;
            }

        @Override
        public int hashCode()
            {
            return Arrays.hashCode(m_abValue);
            }

        @Override
        public boolean equals(Object obj)
            {
            return obj instanceof BitArrayHandle
                && Arrays.equals(m_abValue, ((BitArrayHandle) obj).m_abValue);
            }
        }
    }
