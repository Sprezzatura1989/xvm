package org.xvm.runtime.template;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Op;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;

public class xInt128
        extends xBaseInt128
    {
    public static xInt128 INSTANCE;

    public xInt128(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, true);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    public void initDeclared()
        {
        super.initDeclared();
        }

    /**
     * Converts an object of "this" integer type to the type represented by the template.
     *
     * @return one of the {@link Op#R_NEXT} or {@link Op#R_EXCEPTION} values
     */
    protected int convertToConstrainedType(Frame frame, xConstrainedInteger template,
                                           ObjectHandle hTarget, int iReturn)
        {
        LongLong ll = ((LongLongHandle) hTarget).getValue();

        long lHigh = ll.getHighValue();
        long lLow  = ll.getLowValue();

        if (lHigh > 0 || lHigh < -1 || (lHigh == -1 && lLow >= 0))
            {
            return overflow(frame);
            }

        boolean fNeg = lHigh == -1;

        if (template.f_fSigned && fNeg)
            {
            return overflow(frame);
            }

        if (template instanceof xUInt64)
            {
            // UInt64 fits to any lLow content
            }
        else
            {
            if (lLow < template.f_cMinValue ||
                lLow > template.f_cMaxValue)
                {
                return overflow(frame);
                }
            }

        return frame.assignValue(iReturn, template.makeJavaLong(lLow));
        }
    }
