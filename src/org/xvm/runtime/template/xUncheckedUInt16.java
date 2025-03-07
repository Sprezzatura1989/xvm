package org.xvm.runtime.template;


import org.xvm.asm.ClassStructure;

import org.xvm.runtime.TemplateRegistry;

/**
 * Native unchecked UInt16 support.
 */
public class xUncheckedUInt16
        extends xUncheckedConstrainedInt
    {
    public static xUncheckedUInt16 INSTANCE;

    public xUncheckedUInt16(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, 0L, 0xFFFFL, 16, true);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    protected xConstrainedInteger getComplimentaryTemplate()
        {
        return xUncheckedInt16.INSTANCE;
        }
    }