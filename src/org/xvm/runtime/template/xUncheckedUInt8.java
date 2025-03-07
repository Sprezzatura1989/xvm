package org.xvm.runtime.template;


import org.xvm.asm.ClassStructure;

import org.xvm.runtime.TemplateRegistry;


/**
 * Native unchecked Int8 support.
 */
public class xUncheckedUInt8
        extends xUncheckedConstrainedInt
    {
    public static xUncheckedUInt8 INSTANCE;

    public xUncheckedUInt8(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, 0L, 0xFFL, 8, true);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    protected xConstrainedInteger getComplimentaryTemplate()
        {
        return xUncheckedInt8.INSTANCE;
        }
    }