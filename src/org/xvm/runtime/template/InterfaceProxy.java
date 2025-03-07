package org.xvm.runtime.template;


import java.util.concurrent.CompletableFuture;

import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.CallChain;
import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.ProxyComposition;
import org.xvm.runtime.ServiceContext;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.TypeComposition;



/**
 * Template for proxy-able interfaces.
 */
public class InterfaceProxy
        extends xService
    {
    static public InterfaceProxy INSTANCE;

    public InterfaceProxy(TemplateRegistry templates)
        {
        super(templates, xObject.INSTANCE.f_struct, false);

        INSTANCE = this;
        }

    @Override
    public void initDeclared()
        {
        }

    @Override
    public ClassComposition ensureClass(TypeConstant typeActual)
        {
        throw new UnsupportedOperationException();
        }

    @Override
    public int construct(Frame frame, MethodStructure constructor, ClassComposition clazz,
                         ObjectHandle hParent, ObjectHandle[] ahArg, int iReturn)
        {
        throw new IllegalStateException();
        }

    @Override
    protected ObjectHandle createStruct(Frame frame, ClassComposition clazz)
        {
        throw new IllegalStateException();
        }

    @Override
    protected ExceptionHandle makeImmutable(Frame frame, ObjectHandle hTarget)
        {
        return xException.unsupportedOperation(frame, "makeImmutable");
        }

    @Override
    public int invoke1(Frame frame, CallChain chain, ObjectHandle hTarget, ObjectHandle[] ahVar, int iReturn)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().invoke1(frame, chain, hTarget, ahVar, iReturn);
            }
        return xFunction.makeAsyncHandle(chain).call1(frame, hTarget, ahVar, iReturn);
        }

    @Override
    public int invokeT(Frame frame, CallChain chain, ObjectHandle hTarget, ObjectHandle[] ahVar, int iReturn)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().invokeT(frame, chain, hTarget, ahVar, iReturn);
            }
        return xFunction.makeAsyncHandle(chain).callT(frame, hTarget, ahVar, iReturn);
        }

    @Override
    public int invokeN(Frame frame, CallChain chain, ObjectHandle hTarget, ObjectHandle[] ahVar, int[] aiReturn)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().invokeN(frame, chain, hTarget, ahVar, aiReturn);
            }
        return xFunction.makeAsyncHandle(chain).callN(frame, hTarget, ahVar, aiReturn);
        }

    @Override
    public int getPropertyValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().getPropertyValue(frame, hTarget, idProp, iReturn);
            }

        CompletableFuture<ObjectHandle> cfResult = hProxy.m_context.sendProperty01Request(
                frame, idProp, this::getPropertyValue);

        return frame.assignFutureResult(iReturn, cfResult);
        }

    @Override
    public int getFieldValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp, int iReturn)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().getFieldValue(frame, hTarget, idProp, iReturn);
            }

        throw new IllegalStateException("Invalid context");
        }

    @Override
    public int setPropertyValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp,
                                ObjectHandle hValue)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().setPropertyValue(frame, hTarget, idProp, hValue);
            }

        hProxy.m_context.sendProperty10Request(frame, idProp, hValue, this::setPropertyValue);

        return Op.R_NEXT;
        }

    @Override
    public int setFieldValue(Frame frame, ObjectHandle hTarget, PropertyConstant idProp,
                             ObjectHandle hValue)
        {
        InterfaceProxyHandle hProxy = (InterfaceProxyHandle) hTarget;
        if (frame.f_context == hProxy.m_context)
            {
            hTarget = hProxy.m_hTarget;
            return hTarget.getTemplate().setFieldValue(frame, hTarget, idProp, hValue);
            }

        throw new IllegalStateException("Invalid context");
        }

    public static ObjectHandle makeHandle(ProxyComposition clzProxy, ServiceContext ctx,
                                          ObjectHandle hTarget)
        {
        return new InterfaceProxyHandle(clzProxy, ctx, hTarget);
        }


    // ----- ObjectHandle -----

    public static class InterfaceProxyHandle
            extends ServiceHandle
        {
        private ObjectHandle m_hTarget;

        public InterfaceProxyHandle(TypeComposition clazz, ServiceContext context, ObjectHandle hTarget)
            {
            super(clazz, context);

            m_hTarget = hTarget;
            }

        @Override
        public int hashCode()
            {
            return System.identityHashCode(this);
            }

        @Override
        public boolean equals(Object obj)
            {
            if (this == obj)
                {
                return true;
                }

            return obj instanceof InterfaceProxyHandle &&
                ((InterfaceProxyHandle) obj).m_hTarget == m_hTarget;
            }
        }
    }
