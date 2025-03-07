package org.xvm.runtime.template;


import org.xvm.asm.ClassStructure;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.JavaLong;
import org.xvm.runtime.TemplateRegistry;

/**
 * Abstract super class for all unchecked constrained integers (Int8, UInt16, @Unchecked Int32, ...)
 */
public abstract class xUncheckedConstrainedInt
        extends xConstrainedInteger
    {
    public xUncheckedConstrainedInt(TemplateRegistry templates, ClassStructure structure,
                               long cMinValue, long cMaxValue, int cNumBits, boolean fUnsigned)
        {
        super(templates, structure, cMinValue, cMaxValue, cNumBits, fUnsigned, false);

        f_typeCanonical = pool().ensureAnnotatedTypeConstant(
            pool().clzUnchecked(), null, structure.getCanonicalType());
        f_nMask = cMaxValue - cMinValue;
        f_nSign = cMaxValue + 1; // used only for signed
        }

    @Override
    protected xUncheckedConstrainedInt getUncheckedTemplate()
        {
        return this;
        }

    @Override
    public TypeConstant getCanonicalType()
        {
        return f_typeCanonical;
        }

    @Override
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 + l2;

        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeSub(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 - l2;

        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeMul(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        long l1 = ((JavaLong) hTarget).getValue();
        long l2 = ((JavaLong) hArg).getValue();
        long lr = l1 * l2;

        return frame.assignValue(iReturn, makeJavaLong(lr));
        }

    @Override
    public int invokeNeg(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, makeJavaLong(-l));
        }

    @Override
    public int invokePrev(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l - 1));
        }

    @Override
    public int invokeNext(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        long l = ((JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, makeJavaLong(l + 1));
        }

    @Override
    public int convertLong(Frame frame, long lValue, int iReturn)
        {
        return frame.assignValue(iReturn, makeJavaLong(lValue));
        }

    @Override
    public JavaLong makeJavaLong(long lValue)
        {
        if (f_cNumBits < 64)
            {
            lValue &= f_nMask;
            if ((lValue & f_nSign) != 0 && f_fSigned)
                {
                lValue = -lValue;
                }
            }
        return super.makeJavaLong(lValue);
        }

    private final TypeConstant f_typeCanonical;
    private final long         f_nMask;
    private final long         f_nSign;
    }
