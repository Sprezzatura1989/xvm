package org.xvm.runtime.template;


import org.xvm.asm.ClassStructure;
import org.xvm.asm.MethodStructure;

import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;

import org.xvm.runtime.template.xIntLiteral.VarIntHandle;

import org.xvm.util.PackedInteger;


/**
 * Base class for VarInt/VarUInt integer types.
 */
public abstract class xUnconstrainedInteger
        extends xConst
    {
    protected xUnconstrainedInteger(TemplateRegistry templates, ClassStructure structure)
        {
        super(templates, structure, false);
        }

    @Override
    public void initDeclared()
        {
        // @Op methods
        markNativeMethod("add", THIS, THIS);
        markNativeMethod("sub", THIS, THIS);
        markNativeMethod("mul", THIS, THIS);
        markNativeMethod("div", THIS, THIS);
        markNativeMethod("mod", THIS, THIS);
        markNativeMethod("neg", VOID, THIS);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public boolean isGenericHandle()
        {
        return false;
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
                PackedInteger pi = ((VarIntHandle) hTarget).getValue();
                return frame.assignValue(iReturn, pi.compareTo(PackedInteger.ZERO) >= 0
                    ? hTarget : makeInt(PackedInteger.NEG_ONE));
                }

            case "neg":
                return invokeNeg(frame, hTarget, iReturn);
            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    @Override
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();
        PackedInteger pir = pi1.add(pi2);

        return frame.assignValue(iReturn, makeInt(pir));
        }

    @Override
    public int invokeSub(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();
        PackedInteger pir = pi1.sub(pi2);

        return frame.assignValue(iReturn, makeInt(pir));
        }

    @Override
    public int invokeMul(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();
        PackedInteger pir = pi1.mul(pi2);


        return frame.assignValue(iReturn, makeInt(pir));
        }

    @Override
    public int invokeNeg(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        PackedInteger pi = ((VarIntHandle) hTarget).getValue();

        return frame.assignValue(iReturn, makeInt(pi.negate()));
        }

    @Override
    public int invokePrev(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        PackedInteger pi = ((VarIntHandle) hTarget).getValue();

        return frame.assignValue(iReturn, makeInt(pi.previous()));
        }

    @Override
    public int invokeNext(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        PackedInteger pi = ((VarIntHandle) hTarget).getValue();

        return frame.assignValue(iReturn, makeInt(pi.next()));
        }

    @Override
    public int invokeDiv(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.div(pi2)));
        }

    @Override
    public int invokeMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        PackedInteger piMod = pi1.mod(pi2);
        if (piMod.compareTo(PackedInteger.ZERO) < 0)
            {
            piMod = piMod.add((pi2.compareTo(PackedInteger.ZERO) < 0 ? pi2.negate() : pi2));
            }

        return frame.assignValue(iReturn, makeInt(piMod));
        }

    @Override
    public int invokeShl(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((xIntLiteral.VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.shl(pi2)));
        }

    @Override
    public int invokeShr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.shr(pi2)));
        }

    @Override
    public int invokeShrAll(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.ushr(pi2)));
        }

    @Override
    public int invokeAnd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.and(pi2)));
        }

    @Override
    public int invokeOr(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.or(pi2)));
        }

    @Override
    public int invokeXor(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        return frame.assignValue(iReturn, makeInt(pi1.xor(pi2)));
        }

    @Override
    public int invokeDivMod(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int[] aiReturn)
        {
        PackedInteger pi1 = ((VarIntHandle) hTarget).getValue();
        PackedInteger pi2 = ((VarIntHandle) hArg).getValue();

        PackedInteger piMod = pi1.mod(pi2);
        if (piMod.compareTo(PackedInteger.ZERO) < 0)
            {
            piMod = piMod.add(pi2.compareTo(PackedInteger.ZERO) < 0 ? pi2.negate() : pi2);
            }

        return frame.assignValues(aiReturn, makeInt(pi1.div(pi2)), makeInt(piMod));
        }

    @Override
    public int invokeCompl(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        PackedInteger pi = ((VarIntHandle) hTarget).getValue();

        return frame.assignValue(iReturn, makeInt(pi.complement()));
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        VarIntHandle hThis = (VarIntHandle) hTarget;

        switch (sPropName)
            {
            case "hash":
                return frame.assignValue(iReturn, hThis);
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }

    @Override
    public int buildHashCode(Frame frame, ClassComposition clazz, ObjectHandle hTarget, int iReturn)
        {
        long l = ((ObjectHandle.JavaLong) hTarget).getValue();

        return frame.assignValue(iReturn, xInt64.makeHandle(l));
        }

    // ----- comparison support -----

    @Override
    public int callEquals(Frame frame, ClassComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        VarIntHandle h1 = (xIntLiteral.VarIntHandle) hValue1;
        VarIntHandle h2 = (VarIntHandle) hValue2;

        return frame.assignValue(iReturn, xBoolean.makeHandle(h1.getValue().equals(h2.getValue())));
        }

    @Override
    public int callCompare(Frame frame, ClassComposition clazz,
                           ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        VarIntHandle h1 = (VarIntHandle) hValue1;
        VarIntHandle h2 = (VarIntHandle) hValue2;

        return frame.assignValue(iReturn, xOrdered.makeHandle(h1.getValue().compareTo(h2.getValue())));
        }

    // ----- Object methods -----

    @Override
    protected int buildStringValue(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        PackedInteger pi = ((VarIntHandle) hTarget).getValue();

        return frame.assignValue(iReturn, xString.makeHandle(pi.toString()));
        }

    /**
     * NOTE: we are using the VarIntHandle for objects of UnconstrainedInteger types.
     */
    protected VarIntHandle makeInt(PackedInteger iValue)
        {
        return new VarIntHandle(getCanonicalClass(), iValue, null);
        }
    }
