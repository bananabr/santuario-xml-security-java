/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.swssf.xmlsec.impl.processor.output;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.swssf.xmlsec.config.JCEAlgorithmMapper;
import org.swssf.xmlsec.ext.*;
import org.swssf.xmlsec.ext.stax.XMLSecEvent;
import org.swssf.xmlsec.impl.SignaturePartDef;
import org.swssf.xmlsec.impl.util.DigestOutputStream;
import org.xmlsecurity.ns.configuration.AlgorithmType;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author $Author$
 * @version $Revision$ $Date$
 */
public abstract class AbstractSignatureOutputProcessor extends AbstractOutputProcessor {

    private static final transient Log logger = LogFactory.getLog(AbstractSignatureOutputProcessor.class);

    private final List<SignaturePartDef> signaturePartDefList = new ArrayList<SignaturePartDef>();
    private InternalSignatureOutputProcessor activeInternalSignatureOutputProcessor = null;

    public AbstractSignatureOutputProcessor() throws XMLSecurityException {
        super();
    }

    public List<SignaturePartDef> getSignaturePartDefList() {
        return signaturePartDefList;
    }

    @Override
    public abstract void processEvent(XMLSecEvent xmlSecEvent, OutputProcessorChain outputProcessorChain)
            throws XMLStreamException, XMLSecurityException;

    protected InternalSignatureOutputProcessor getActiveInternalSignatureOutputProcessor() {
        return activeInternalSignatureOutputProcessor;
    }

    protected void setActiveInternalSignatureOutputProcessor(
            InternalSignatureOutputProcessor activeInternalSignatureOutputProcessor) {
        this.activeInternalSignatureOutputProcessor = activeInternalSignatureOutputProcessor;
    }

    public class InternalSignatureOutputProcessor extends AbstractOutputProcessor {

        private SignaturePartDef signaturePartDef;
        private QName startElement;
        private int elementCounter = 0;

        private OutputStream bufferedDigestOutputStream;
        private DigestOutputStream digestOutputStream;
        private Transformer transformer;

        public InternalSignatureOutputProcessor(SignaturePartDef signaturePartDef, QName startElement)
                throws XMLSecurityException, NoSuchProviderException, NoSuchAlgorithmException {
            super();
            this.addBeforeProcessor(InternalSignatureOutputProcessor.class.getName());
            this.signaturePartDef = signaturePartDef;
            this.startElement = startElement;
        }

        @Override
        public void init(OutputProcessorChain outputProcessorChain) throws XMLSecurityException {
            try {
                AlgorithmType algorithmID = JCEAlgorithmMapper.getAlgorithmMapping(getSecurityProperties().getSignatureDigestAlgorithm());
                MessageDigest messageDigest;
                if (algorithmID.getJCEProvider() != null) {
                    messageDigest = MessageDigest.getInstance(algorithmID.getJCEName(), algorithmID.getJCEProvider());
                } else {
                    messageDigest = MessageDigest.getInstance(algorithmID.getJCEName());
                }
                this.digestOutputStream = new DigestOutputStream(messageDigest);
                this.bufferedDigestOutputStream = new BufferedOutputStream(digestOutputStream);

                if (signaturePartDef.getTransformAlgo() != null) {
                    List<String> inclusiveNamespaces = new ArrayList<String>(1);
                    inclusiveNamespaces.add("#default");
                    Transformer transformer = XMLSecurityUtils.getTransformer(inclusiveNamespaces,
                            this.bufferedDigestOutputStream, signaturePartDef.getC14nAlgo());
                    this.transformer = XMLSecurityUtils.getTransformer(transformer, null, signaturePartDef.getTransformAlgo());
                } else {
                    transformer = XMLSecurityUtils.getTransformer(null, this.bufferedDigestOutputStream, signaturePartDef.getC14nAlgo());
                }
            } catch (NoSuchMethodException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (InstantiationException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (IllegalAccessException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (InvocationTargetException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (NoSuchAlgorithmException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            } catch (NoSuchProviderException e) {
                throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
            }

            super.init(outputProcessorChain);
        }

        @Override
        public void processEvent(XMLSecEvent xmlSecEvent, OutputProcessorChain outputProcessorChain)
                throws XMLStreamException, XMLSecurityException {

            transformer.transform(xmlSecEvent);

            switch (xmlSecEvent.getEventType()) {
                case XMLStreamConstants.START_ELEMENT:
                    elementCounter++;
                    break;
                case XMLStreamConstants.END_ELEMENT:
                    elementCounter--;

                    if (elementCounter == 0 && xmlSecEvent.asEndElement().getName().equals(this.startElement)) {
                        try {
                            bufferedDigestOutputStream.close();
                        } catch (IOException e) {
                            throw new XMLSecurityException(XMLSecurityException.ErrorCode.FAILED_SIGNATURE, e);
                        }
                        String calculatedDigest = new String(Base64.encodeBase64(this.digestOutputStream.getDigestValue()));
                        if (logger.isDebugEnabled()) {
                            logger.debug("Calculated Digest: " + calculatedDigest);
                        }
                        signaturePartDef.setDigestValue(calculatedDigest);

                        outputProcessorChain.removeProcessor(this);
                        //from now on signature is possible again
                        setActiveInternalSignatureOutputProcessor(null);
                    }
                    break;
            }
            outputProcessorChain.processEvent(xmlSecEvent);
        }
    }
}
