package org.xvm.runtime.template._native.fs;


import java.io.IOException;

import java.nio.file.Path;

import org.xvm.asm.ClassStructure;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.template.collections.xArray.Mutability;
import org.xvm.runtime.template.collections.xByteArray;

import org.xvm.util.Handy;


/**
 * Native OSFile implementation.
 */
public class xOSFile
        extends OSFileNode
    {
    public xOSFile(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure);
        }

    @Override
    public void initDeclared()
        {
        super.initDeclared();

        markNativeProperty("contents");
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        NodeHandle hNode = (NodeHandle) hTarget;
        switch (sPropName)
            {
            case "contents":
                {
                Path path = hNode.f_path;

                try
                    {
                    byte[] ab = Handy.readFileBytes(path.toFile());
                    return frame.assignValue(iReturn, xByteArray.makeHandle(ab, Mutability.Constant));
                    }
                catch (IOException e)
                    {
                    return raisePathException(frame, e, hNode);
                    }

                }
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }
    }
