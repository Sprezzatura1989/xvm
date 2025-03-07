package org.xvm.runtime.template;


import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.asm.constants.AnnotatedTypeConstant;
import org.xvm.asm.constants.IntConstant;
import org.xvm.asm.constants.SignatureConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.JavaLong;
import org.xvm.runtime.TemplateRegistry;

import org.xvm.runtime.template.collections.BitBasedArray;
import org.xvm.runtime.template.collections.BitBasedArray.BitArrayHandle;
import org.xvm.runtime.template.collections.xArray.Mutability;
import org.xvm.runtime.template.collections.xByteArray;
import org.xvm.runtime.template.collections.xByteArray.ByteArrayHandle;

import org.xvm.util.PackedInteger;


/**
 * Abstract base class for constrained integers that fit into 64 bits
 * (Int8, UInt16, @Unchecked Int32, ...)
 */
public abstract class xConstrainedInteger
        extends xConst
    {
    protected xConstrainedInteger(TemplateRegistry templates, ClassStructure structure,
                                  long cMinValue, long cMaxValue,
                                  int cNumBits, boolean fUnsigned, boolean fChecked)
        {
        super(templates, structure, false);

        f_cMinValue = cMinValue;
        f_cMaxValue = cMaxValue;
        f_cNumBits  = cNumBits;
        f_fChecked  = fChecked;
        f_fSigned   = !fUnsigned;

        f_cAddCheckShift = 64 - cNumBits;
        f_cMulCheckShift = fUnsigned ? (cNumBits / 2) : (cNumBits / 2 - 1);
        }

    @Override
    public void initDeclared()
        {
        String sName = f_struct.getName();

        markNativeProperty("magnitude");
        markNativeProperty("bitCount");
        markNativeProperty("leftmostBit");
        markNativeProperty("rightmostBit");
        markNativeProperty("leadingZeroCount");
        markNativeProperty("trailingZeroCount");

        markNativeMethod("toUnchecked", VOID, null);

        markNativeMethod("toInt8"  , VOID, sName.equals("Int8")   ? THIS : new String[]{"Int8"});
        markNativeMethod("toInt16" , VOID, sName.equals("Int16")  ? THIS : new String[]{"Int16"});
        markNativeMethod("toInt32" , VOID, sName.equals("Int32")  ? THIS : new String[]{"Int32"});
        markNativeMethod("toInt"   , VOID, sName.equals("Int64")  ? THIS : new String[]{"Int64"});
        markNativeMethod("toByte"  , VOID, sName.equals("UInt8")  ? THIS : new String[]{"UInt8"});
        markNativeMethod("toUInt16", VOID, sName.equals("UInt16") ? THIS : new String[]{"UInt16"});
        markNativeMethod("toUInt32", VOID, sName.equals("UInt32") ? THIS : new String[]{"UInt32"});
        markNativeMethod("toUInt"  , VOID, sName.equals("UInt64") ? THIS : new String[]{"UInt64"});

        markNativeMethod("toInt128"      , VOID, new String[]{"Int128"});
        markNativeMethod("toUInt128"     , VOID, new String[]{"UInt128"});
        markNativeMethod("toVarInt"      , VOID, new String[]{"VarInt"});
        markNativeMethod("toVarUInt"     , VOID, new String[]{"VarUInt"});
        markNativeMethod("toVarFloat"    , VOID, new String[]{"VarFloat"});
        markNativeMethod("toVarDec"      , VOID, new String[]{"VarDec"});
        markNativeMethod("toChar"        , VOID, new String[]{"Char"});
        markNativeMethod("toBooleanArray", VOID, new String[]{"collections.Array<Boolean>"});
        markNativeMethod("toBitArray"    , VOID, new String[]{"collections.Array<Bit>"});

        markNativeMethod("rotateLeft"   , INT , THIS);
        markNativeMethod("rotateRight"  , INT , THIS);
        markNativeMethod("retainLSBits" , INT , THIS);
        markNativeMethod("retainMSBits" , INT , THIS);
        markNativeMethod("reverseBits"  , VOID, THIS);
        markNativeMethod("reverseBytes" , VOID, THIS);
        markNativeMethod("stepsTo"      , THIS, INT );

        // @Op methods
        markNativeMethod("abs"          , VOID, THIS);
        markNativeMethod("add"          , THIS, THIS);
        markNativeMethod("sub"          , THIS, THIS);
        markNativeMethod("mul"          , THIS, THIS);
        markNativeMethod("div"          , THIS, THIS);
        markNativeMethod("mod"          , THIS, THIS);
        markNativeMethod("neg"          , VOID, THIS);
        markNativeMethod("and"          , THIS, THIS);
        markNativeMethod("or"           , THIS, THIS);
        markNativeMethod("xor"          , THIS, THIS);
        markNativeMethod("not"          , VOID, THIS);
        markNativeMethod("shiftLeft"    , INT, THIS);
        markNativeMethod("shiftRight"   , INT, THIS);
        markNativeMethod("shiftAllRight", INT, THIS);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public ClassTemplate getTemplate(TypeConstant type)
        {
        return type instanceof AnnotatedTypeConstant &&
            ((AnnotatedTypeConstant) type).getAnnotationClass().equals(pool().clzUnchecked())
             ? getUncheckedTemplate()
             : this;
        }

    /**
     * @return a complimentary template (signed for unsigned and vice versa)
     */
    abstract protected xConstrainedInteger getComplimentaryTemplate();

    /**
     * @return an unchecked template for this type
     */
    abstract protected xUncheckedConstrainedInt getUncheckedTemplate();

    @Override
    public boolean isGenericHandle()
        {
        return false;
        }

    @Override
    public int createConstHandle(Frame frame, Constant constant)
        {
        if (constant instanceof IntConstant)
            {
            frame.pushStack(new JavaLong(getCanonicalClass(),
                    (((IntConstant) constant).getValue().getLong())));
            return Op.R_NEXT;
            }

        return super.createConstHandle(frame, constant);
        }

    @Override
    public int construct(Frame frame, MethodStructure constructor, ClassComposition clazz,
                         ObjectHandle hParent, ObjectHandle[] ahVar, int iReturn)
        {
        // make sure this is a Binary-based constructor
        SignatureConstant sig = constructor.getIdentityConstant().getSignature();
        if (sig.getParamCount() == 1 && sig.getRawParams()[0].equals(xByteArray.INSTANCE.getCanonicalType()))
            {
            ByteArrayHandle hBytes = (ByteArrayHandle) ahVar[0];
            byte[]          abVal  = hBytes.m_abValue;

            int cBytes = abVal.length;
            if (cBytes != f_cNumBits / 8)
                {
                return frame.raiseException(xException.illegalArgument(frame, "Invalid byte count: " + cBytes));
                }

            long lResult = 0;
            for (int i = 0; i < cBytes; i++)
                {
                lResult = lResult << 8 | abVal[i];
                }

            return convertLong(frame, lResult, iReturn);
            }
        else
            {
            return frame.raiseException(xException.unsupportedOperation(frame));
            }
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        switch (sPropName)
            {
            case "magnitude":
                {
                if (f_fSigned)
                    {
                    long l = ((JavaLong) hTarget).getValue();
                    hTarget = getComplimentaryTemplate().makeJavaLong(l < 0 ? -l : l);
                    }
                return frame.assignValue(iReturn, hTarget);
                }

            case "digitCount":
                {
                long l = ((JavaLong) hTarget).getValue();

                if (l < 0)
                    {
                    l = -l;
                    }

                int cDigits = 19;
                if (l >= 0)
                    {
                    long n = 10;
                    for (cDigits = 1; cDigits < 19; ++cDigits)
                        {
                        if (l < n)
                            {
                            break;
                            }
                        n *= 10;
                        }
                    }

                return frame.assignValue(iReturn, xInt64.makeHandle(cDigits));
                }

            case "bitCount":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, xInt64.makeHandle(Long.bitCount(l)));
                }

            case "leftmostBit":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, makeJavaLong(Long.highestOneBit(l)));
                }

            case "rightmostBit":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, makeJavaLong(Long.lowestOneBit(l)));
                }

            case "leadingZeroCount":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, makeJavaLong(Long.numberOfLeadingZeros(l)));
                }

            case "trailingZeroCount":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, makeJavaLong(Long.numberOfTrailingZeros(l)));
                }
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }

    @Override
    public int invokeNative1(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle hArg, int iReturn)
        {
        switch (method.getName())
            {
            case "add":
                return invokeAdd(frame, hTarget, hArg, iReturn);

            case "sub":
                return invokeSub(frame, hTarget, hArg, iReturn);

            case "mul":
                return invokeMul(frame, hTarget, hArg, iReturn);

            case "div":
                return invokeDiv(frame, hTarget, hArg, iReturn);

            case "mod":
                return invokeMod(frame, hTarget, hArg, iReturn);

            case "and":
                return invokeAnd(frame, hTarget, hArg, iReturn);

            case "or":
                return invokeOr(frame, hTarget, hArg, iReturn);

            case "xor":
                return invokeXor(frame, hTarget, hArg, iReturn);

            case "not":
                return invokeCompl(frame, hTarget, iReturn);

            case "shiftLeft":
                return invokeShl(frame, hTarget, hArg, iReturn);

            case "shiftRight":
                return invokeShr(frame, hTarget, hArg, iReturn);

            case "shiftAllRight":
                return invokeShrAll(frame, hTarget, hArg, iReturn);
            }

        return super.invokeNative1(frame, method, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeNativeN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle[] ahArg, int iReturn)
        {
        switch (method.getName())
            {
            case "abs":
                {
                if (f_fSigned)
                    {
                    long l = ((JavaLong) hTarget).getValue();
                    return frame.assignValue(iReturn, l >= 0 ? hTarget : makeJavaLong(-l));
                    }
                return frame.assignValue(iReturn, hTarget);
                }

            case "toUnchecked":
                {
                long l = ((JavaLong) hTarget).getValue();
                return frame.assignValue(iReturn, getUncheckedTemplate().makeJavaLong(l));
                }

            case "toInt8":
            case "toInt16":
            case "toInt32":
            case "toInt":
            case "toInt128":
            case "toByte":
            case "toUInt16":
            case "toUInt32":
            case "toUInt":
            case "toUInt128":
            case "toVarInt":
            case "toVarUInt":
            case "toVarFloat":
            case "toVarDec":
            case "toChar":
            case "toBooleanArray":
            case "toBitArray":
                {
                TypeConstant  typeRet  = method.getReturn(0).getType();
                ClassTemplate template = f_templates.getTemplate(typeRet);

                if (template == this)
                    {
                    return frame.assignValue(iReturn, hTarget);
                    }

                if (template instanceof xConstrainedInteger)
                    {
                    xConstrainedInteger templateTo = (xConstrainedInteger) template;
                    long                lValue     = ((JavaLong) hTarget).getValue();

                    // there is one overflow case that needs to be handled here: UInt64 -> Int*
                    if (lValue < 0 && this instanceof xUInt64)
                        {
                        return templateTo.overflow(frame);
                        }

                    return templateTo.convertLong(frame, lValue, iReturn);
                    }

                if (template instanceof xBaseInt128)
                    {
                    xBaseInt128 templateTo = (xBaseInt128) template;
                    long        lValue     = ((JavaLong) hTarget).getValue();

                    if (f_fSigned && lValue < 0 && !templateTo.f_fSigned)
                        {
                        // cannot assign negative value to the unsigned type
                        return overflow(frame);
                        }

                    return templateTo.convertLong(frame, lValue, iReturn);
                    }

                if (template instanceof xChar)
                    {
                    long l = ((JavaLong) hTarget).getValue();
                    if (l > 0x10_FFFF)
                        {
                        l &= 0x0F_FFFF;
                        }
                    return frame.assignValue(iReturn, xChar.makeHandle(l));
                    }

                if (template instanceof BitBasedArray)
                    {
                    long l = ((JavaLong) hTarget).getValue();
                    return frame.assignValue(iReturn, new BitArrayHandle(template.getCanonicalClass(),
                        toByteArray(l), f_cNumBits, Mutability.Constant));
                    }

                break;
                }

            case "neg":
                return invokeNeg(frame, hTarget, iReturn);

            case "rotateLeft":
            case "rotateRight":
            case "reverseBits":
            case "reverseBytes":
                throw new UnsupportedOperationException("subclass implementation required for " + method.getName());

            case "truncate":
                {
                long lValue = ((JavaLong) hTarget ).getValue();
                long cBits  = ((JavaLong) ahArg[0]).getValue();
                if (cBits < 0 || cBits > f_cNumBits)
                    {
                    return frame.raiseException(xException.outOfBounds(frame, cBits, f_cNumBits));
                    }

                if (cBits == 0)
                    {
                    lValue = 0;
                    }
                else if (cBits != f_cNumBits)
                    {
                    lValue = lValue & (0xFFFFFFFFFFFFFFFFL >>> (64-cBits));
                    }

                return frame.assignValue(iReturn, makeJavaLong(lValue));
                }

            case "stepsTo":
                {
                long lFrom = ((JavaLong) hTarget ).getValue();
                long lTo   = ((JavaLong) ahArg[0]).getValue();
                return frame.assignValue(iReturn, makeJavaLong(lTo - lFrom));
                }
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    @Override
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 + l2;

        if (f_fChecked && (((l1 ^ lr) & (l2 ^ lr)) << f_cAddCheckShift) < 0)
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeSub(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 - l2;

        if (f_fChecked && (((l1 ^ l2) & (l1 ^ lr)) << f_cAddCheckShift) < 0)
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeMul(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 * l2;

        if (f_fChecked)
            {
            long a1 = Math.abs(l1);
            long a2 = Math.abs(l2);
            if ((a1 | a2) >>> f_cMulCheckShift != 0)
                {
                // see Math.multiplyExact()
                if (((l2 != 0) && (lr / l2 != l1)) ||
                        (l1 == f_cMinValue && l2 == -1) ||
                        lr > f_cMaxValue || lr < f_cMinValue)
                    {
                    return overflow(frame);
                    }
                }
            }


        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeNeg(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        if (f_fChecked && (!f_fSigned || l == f_cMinValue))
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(-l));
        }

    @Override
    public int invokePrev(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        if (f_fChecked && l == f_cMinValue)
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(l - 1));
        }

    @Override
    public int invokeNext(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        if (f_fChecked && l == f_cMaxValue)
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(l + 1));
        }

    @Override
    public int invokeDiv(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        if (l2 == 0)
            {
            return overflow(frame);
            }
        return frame.assignValue(iReturn, makeJavaLong(l1 / l2));
        }

    @Override
    public int invokeMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        if (l2 == 0)
            {
            return overflow(frame);
            }

        long lMod = l1 % l2;
        if (lMod < 0)
            {
            lMod += (l2 < 0 ? -l2 : l2);
            }

        return frame.assignValue(iReturn, makeJavaLong(lMod));
        }

    @Override
    public int invokeShl(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 << l2));
        }

    @Override
    public int invokeShr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 >> l2));
        }

    @Override
    public int invokeShrAll(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 >>> l2));
        }

    @Override
    public int invokeAnd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 & l2));
        }

    @Override
    public int invokeOr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 | l2));
        }

    @Override
    public int invokeXor(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l1 ^ l2));
        }

    @Override
    public int invokeDivMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int[] aiReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();

        long lMod = l1 % l2;
        if (lMod < 0)
            {
            lMod += (l2 < 0 ? -l2 : l2);
            }

        return frame.assignValues(aiReturn, makeJavaLong(l1 / l2), makeJavaLong(lMod));
        }

    @Override
    public int invokeCompl(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, makeJavaLong(~l));
        }

    @Override
    public int buildHashCode(Frame frame, ClassComposition clazz, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, xInt64.makeHandle(l));
        }

    // ----- comparison support -----

    @Override
    public int callEquals(Frame frame, ClassComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        JavaLong h1 = (JavaLong) hValue1;
        JavaLong h2 = (JavaLong) hValue2;

        return frame.assignValue(iReturn,
            xBoolean.makeHandle(h1.getValue() == h2.getValue()));
        }

    @Override
    public int callCompare(Frame frame, ClassComposition clazz,
                           ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        JavaLong h1 = (JavaLong) hValue1;
        JavaLong h2 = (JavaLong) hValue2;

        return frame.assignValue(iReturn,
            xOrdered.makeHandle(Long.compare(h1.getValue(), h2.getValue())));
        }

    /**
     * Convert a PackedInteger value into a handle for the type represented by this template.
     *
     * @return one of the {@link Op#R_NEXT} or {@link Op#R_EXCEPTION} values
     */
    public int convertLong(Frame frame, PackedInteger piValue, int iReturn)
        {
        return piValue.isBig()
            ? overflow(frame)
            : convertLong(frame, piValue.getLong(), iReturn);
        }

    /**
     * Convert a long value into a handle for the type represented by this template.
     *
     * @return one of the {@link Op#R_NEXT} or {@link Op#R_EXCEPTION} values
     */
    public int convertLong(Frame frame, long lValue, int iReturn)
        {
        if (lValue < f_cMinValue || lValue > f_cMaxValue)
            {
            return overflow(frame);
            }

        return frame.assignValue(iReturn, makeJavaLong(lValue));
        }

    @Override
    protected int buildStringValue(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, xString.makeHandle(String.valueOf(l)));
        }

    /**
     * Raise an overflow exception.
     *
     * @return {@link Op#R_EXCEPTION}
     */
    protected int overflow(Frame frame)
        {
        return frame.raiseException(xException.outOfBounds(frame, f_struct.getName() + " overflow"));
        }

    /**
     * Create a JavaLong handle for the type represented by this template.
     *
     * @param lValue  the underlying long value
     *
     * @return the corresponding handle
     */
    public JavaLong makeJavaLong(long lValue)
        {
        return new JavaLong(getCanonicalClass(), lValue);
        }


    // ----- helpers -----

    protected byte[] toByteArray(long l)
        {
        switch (f_cNumBits)
            {
            case 64:
                return new byte[]
                    {
                    (byte) l,
                    (byte) (l >> 8),
                    (byte) (l >> 16),
                    (byte) (l >> 24),
                    (byte) (l >> 32),
                    (byte) (l >> 40),
                    (byte) (l >> 48),
                    (byte) (l >> 56)
                    };

            case 32:
                return new byte[] {
                    (byte) l,
                    (byte) (l >> 8),
                    (byte) (l >> 16),
                    (byte) (l >> 24),
                    };

            case 16:
                return new byte[] {
                    (byte) l,
                    (byte) (l >> 8),
                    };

            case 8:
                return new byte[] {
                    (byte) l,
                    };

            default:
                throw new IllegalStateException();
            }
        }

    // ----- fields -----

    protected final long f_cMinValue;
    protected final long f_cMaxValue;
    protected final int  f_cNumBits;
    protected final int  f_cAddCheckShift;
    protected final int  f_cMulCheckShift;

    protected final boolean f_fChecked;
    protected final boolean f_fSigned;
    }
