/* Copyright (C) 2011 [Gobierno de Espana]
 * This file is part of "Cliente @Firma".
 * "Cliente @Firma" is free software; you can redistribute it and/or modify it under the terms of:
 *   the GNU General Public License as published by the Free Software Foundation;
 *     either version 2 of the License, or (at your option) any later version.
 *   or The European Software License; either version 1.1 or (at your option) any later version.
 * Date: 11/01/11
 * You may contact the copyright holder at: soporte.afirma5@mpt.es
 */

package es.gob.afirma.signers.pades;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.util.GregorianCalendar;
import java.util.Properties;

import com.lowagie.text.DocumentException;
import com.lowagie.text.pdf.PdfDictionary;
import com.lowagie.text.pdf.PdfName;
import com.lowagie.text.pdf.PdfSignatureAppearance;
import com.lowagie.text.pdf.PdfString;

import es.gob.afirma.core.AOException;
import es.gob.afirma.core.misc.AOUtil;
import es.gob.afirma.core.signers.AOSignConstants;
import es.gob.afirma.core.signers.AdESPolicy;
import es.gob.afirma.signers.cades.CAdESSignerMetadataHelper;
import es.gob.afirma.signers.cades.CAdESTriPhaseSigner;
import es.gob.afirma.signers.cades.CommitmentTypeIndicationsHelper;
import es.gob.afirma.signers.tsp.pkcs7.CMSTimestamper;
import es.gob.afirma.signers.tsp.pkcs7.TsaParams;
/** Clase para la firma electr&oacute;nica en tres fases de ficheros Adobe PDF en formato PAdES.
 * <p>No firma PDF cifrados.</p>
 * <p>Necesita iText 2.1.7 con modificaciones espec&iacute;ficas.</p>
 * <p>Esta clase no interacciona directamente en ning&uacute;n momento con el usuario ni usa interfaces gr&aacute;ficos.</p>
 * <p>La firma electr&oacute;nica en tres fases est&aacute; pensada para entornos donde la clave privada reside
 * en un sistema con al menos alguna de las siguientes restricciones:</p>
 * <ul>
 *  <li>
 *   El sistema no es compatible con el Cliente @firma. En este caso, dado que el 95% del c&oacute;digo se
 *   ejecuta en un sistema externo, solo es necesario portar el 5% restante.
 *  </li>
 *  <li>
 *   El sistema tiene unas capacidades muy limitadas en cuanto a proceso computacional, memoria o comunicaciones por
 *   red. En este caso, el sistema solo realiza una operaci&oacute;n criptogr&aacute;fica, una firma PKCS#1,
 *   mucho menos demandante de potencia de proceso que una firma completa PAdES, y, adicionalmente, no trata el
 *   documento a firmar completo, sino &uacute;icamente una prque&ntilde;a cantidad de datos resultante de un
 *   pre-proceso (la pre-firma) realizado por el sistema externo, lo que resulta en un enorme decremento en las necesidades
 *   de memoria y transmisi&oacute;n de datos.
 *  </li>
 *  <li>
 *   Por motivos de seguridad, el documento a firmar no puede salir de un sistema externo. Como se ha descrito en el punto
 *   anterior, en este caso el documento jam&aacute;s sale del sistema externo, sino que se transfiere &uacute;nicamente
 *   el resultado de la pre-firma, desde la cual es imposible reconstruir el documento original.
 *  </li>
 * </ul>
 * <p>
 *  Estos condicionantes convierten la firma trif&aacute;sica en una opci&oacute;n perfectamente adaptada a los
 *  dispositivos m&oacute;viles, donde se dan tanto la heterogeneidad de sistemas operativos (Apple iOS, Google
 *  Android, RIM BlackBerry, Microsoft Windows Phone, etc.) y las limitaciones en potencia de proceso, memoria
 *  y comunicaciones; en estas &uacute;ltimas hay que tener en cuenta el coste, especialmente si estamos haciendo
 *  uso de una red de otro operador en itinerancia (<i>roaming</i>).
 * </p>
 * <p>
 *  El funcionamiento t&iacute;pico de una firma trif&aacute;sica en la que intervienen un disposotivo m&oacute;vil,
 *  un servidor Web (que hace la pre-firma y la post-firma) y un servidor documental podr&iacute;a ser el siguiente:
 * </p>
 * <p><b>Pre-firma:</b></p>
 * <p style="text-align: center;"><img src="doc-files/PAdESTriPhaseSigner-1.png" alt="Pre-firma"></p>
 * <ul>
 *  <li>El dispositivo m&oacute;vil solicita una pre-firma al servidor Web indicando un identificador de documento.</li>
 *  <li>El servidor Web solicita el documento a servidor documental.</li>
 *  <li>
 *   El servidor documental entrega el documento al servidor Web.<br>Es importante recalcar que el servidor
 *   documental no necesita almacenar ning&uacute;n dato de sesi&oacute;n y que este no est&aacute; expuesto a Internet
 *   de forma directa en ning&uacute;n momento.
 *  </li>
 *  <li>
 *   El servidor Web calcula la pre-firma, entregando el resultado (muy peque&ntilde;o en tama&ntilde;o) al dispositivo.<br>
 *   Es importante recalcar que el servidor Web no necesita almacenar ning&uacute;n dato de sesi&oacute;n ni
 *   exponer los documentos directamente al dispositivo.
 *  </li>
 * </ul>
 * <p><b>Firma:</b></p>
 * <p style="text-align: center;"><img src="doc-files/PAdESTriPhaseSigner-2.png" alt="Firma"></p>
 * <ul>
 *  <li>
 *   El dispositivo m&oacute;vil realiza, de forma completamente aislada una firma electr&oacute;nica
 *   simple (computacionalmente ligera) de los datos de la pre-firma. La clave privada del usuario nunca sale
 *   del dispositivo y no se expone externamente en ning&uacute;n momento.
 *  </li>
 * </ul>
 * <p><b>Post-firma:</b></p>
 * <p style="text-align: center;"><img src="doc-files/PAdESTriPhaseSigner-3.png" alt="Post-firma"></p>
 * <ul>
 *  <li>
 *   El dispositivo m&oacute;vil solicita una post-firma al servidor Web indicando un identificador de
 *   documento y proporcionando el resultado de su pre-firma firmada.
 *  </li>
 *  <li>El servidor Web solicita el documento a servidor documental.</li>
 *  <li>El servidor documental entrega el documento al servidor Web.</li>
 *  <li>
 *   El servidor Web calcula la post-firma y compone el documento final firmado, entregando el resultado
 *   al servidor documental para su almac&eacute;n.
 *  </li>
 *  <li>El servidor documental almacena el nuevo documento y devuelve un identificador al servidor Web.</li>
 *  <li>
 *   El servidor Web comunica al dispositivo el &eacute;xito de la operaci&oacute;n y el identificador del fichero
 *   ya firmado y almacenado.
 *  </li>
 * </ul>
 * <p>
 *  Es conveniente tener en cuenta al usar firmas trif&aacute;sicas que es necesario disponer de un mecanismo
 *  para que el usuario pueda ver en todo momento los documentos que est&aacute; firmando (una copia que refleje
 *  con fidelidad el contenido firmado puede ser suficiente) para evitar situaciones de repudio.
 * </p>
 * <p>
 *  Una pecualiaridad de las firmas trif&aacute;sicas PAdES es que en la generaci&oacute;n o firma de un PDF se genera de forma
 *  autom&aacute;tica un identificador &uacute;nico y aleatorio llamado <i>FILE_ID</i>, que hace que al firmar en momentos diferentes
 *  dos PDF exactamente iguales se generen PDF con un <i>FILE_ID</i> distinto, y, por lo tanto, con la huella
 *  digital de la firma electr&oacute;nica distinta.<br>
 *  Para solventar este inconveniente, en la firma trif&aacute;sica PDF, se considera prefirma tanto la totalidad de los atributos
 *  CAdES a firmar como el <i>FILE_ID</i> del PDF que se debe compartir entre pre-firma y post-firma.
 * </p>
 *  Notas sobre documentos <i>certificados</i>:<br>
 *  Si un PDF firmado se ha certificado (por ejemplo, a&ntilde;adiendo una firma electr&oacute;nica usando Adobe Reader), cualquier
 *  modificaci&oacute;n posterior del fichero (como la adici&oacute;n de nuevas firmas con este m&eacute;todo) invalidar&aacute;
 *  las firmas previamente existentes.<br>
 *  Consulte la documentaci&oacute;n de la opci&oacute;n <code>allowSigningCertifiedPdfs</code> para establecer un comportamiento por
 *  defecto respecto a los PDF certificados.
 * @author Tom&aacute;s Garc&iacute;a-Mer&aacute;s */
public final class PAdESTriPhaseSigner {

	private static final String PDF_OID = "1.2.826.0.1089.1.5"; //$NON-NLS-1$
	private static final String PDF_DESC = "Documento en formato PDF"; //$NON-NLS-1$

    /** Referencia a la &uacute;ltima p&aacute;gina del documento PDF. */
    public static final int LAST_PAGE = -666;

    /** Versi&oacute;n de iText necesaria para el uso de esta clase (2.1.7). */
    public static final String ITEXT_VERSION = "2.1.7"; //$NON-NLS-1$

    private static final int CSIZE = 27000;

    private PAdESTriPhaseSigner() {
    	// No permitimos la instanciacion
    }

    /** Obtiene la pre-firma PAdES/CAdES de un PDF (atributos CAdES a firmar)
     * @param digestAlgorithmName Nombre del algoritmo de huella digital usado para la firma. Debe usarse exactamente el mismo valor en la post-firma.
     * <p>Se aceptan los siguientes algoritmos en el par&aacute;metro <code>digestAlgorithmName</code>:</p>
     * <ul>
     *  <li><i>SHA1</i></li>
     *  <li><i>MD5</i> (no recomendado por vulnerable)</li>
     *  <li><i>MD2</i> (no recomendado por vulnerable)</li>
     *  <li><i>SHA-256</i></li>
     *  <li><i>SHA-384</i></li>
     *  <li><i>SHA-512</i></li>
     * </ul>
     * @param inPDF PDF a firmar. Debe usarse exactamente el mismo documento en la post-firma.
     * @param signerCertificateChain Cadena de certificados del firmante Debe usarse exactamente la misma cadena de certificados en la post-firma.
     * @param xParams Par&aacute;metros adicionales para la firma (<a href="doc-files/extraparams.html">detalle</a>). Deben usarse exactamente los mismos valores en la post-firma.
     * @param signTime Momento de la firma. Debe usarse exactamente el mismo valor en la post-firma.
     * @return pre-firma CAdES/PAdES (atributos CAdES a firmar)
     * @throws IOException En caso de errores de entrada / salida
     * @throws AOException En caso de cualquier otro tipo de error
     * @throws DocumentException En caso de errores en el XML de sesi&oacute;n */
    public static PdfSignResult preSign(final String digestAlgorithmName,
                                           final byte[] inPDF,
                                           final Certificate[] signerCertificateChain,
                                           final GregorianCalendar signTime,
                                           final Properties xParams) throws IOException,
                                                                            AOException,
                                                                            DocumentException {

        final Properties extraParams = xParams != null ? xParams : new Properties();

        final PdfTriPhaseSession ptps = PdfSessionManager.getSessionData(inPDF, signerCertificateChain, signTime, extraParams);

	    // La norma PAdES establece que si el algoritmo de huella digital es SHA1 debe usarse SigningCertificateV2, y en cualquier
	    // otro caso deberia usarse SigningCertificateV2
	    boolean signingCertificateV2;
	    if (extraParams.containsKey("signingCertificateV2")) { //$NON-NLS-1$
	    	signingCertificateV2 = Boolean.parseBoolean(extraParams.getProperty("signingCertificateV2")); //$NON-NLS-1$
	    }
	    else {
	    	signingCertificateV2 = !"SHA1".equals(AOSignConstants.getDigestAlgorithmName(digestAlgorithmName));	 //$NON-NLS-1$
	    }

        final byte[] original = AOUtil.getDataFromInputStream(ptps.getSAP().getRangeStream());

        // Calculamos el MessageDigest
        final byte[] md;
        try {
            md = MessageDigest.getInstance(AOSignConstants.getDigestAlgorithmName(digestAlgorithmName)).digest(original);
        }
        catch (final NoSuchAlgorithmException e) {
            throw new AOException("El algoritmo de huella digital no es valido: " + e, e); //$NON-NLS-1$
        }

        // Pre-firma CAdES
        return new PdfSignResult(
            ptps.getFileID(),
            CAdESTriPhaseSigner.preSign(
                AOSignConstants.getDigestAlgorithmName(digestAlgorithmName), // Algoritmo de huella digital
                null, // Datos a firmar (null por ser explicita))
                signerCertificateChain, // Cadena de certificados del firmante
                AdESPolicy.buildAdESPolicy(extraParams), // Politica de firma
                signingCertificateV2, // signingCertificateV2
                md, // Valor de la huella digital del contenido
                signTime.getTime(), // Fecha de la firma (debe establecerse externamente para evitar desincronismos en la firma trifasica)
                true, // Modo PAdES
                PDF_OID,
                PDF_DESC,
                CommitmentTypeIndicationsHelper.getCommitmentTypeIndications(extraParams),
                CAdESSignerMetadataHelper.getCAdESSignerMetadata(extraParams)
            ),
            null, // Sello de tiempo
            signTime,
            extraParams
        );
    }

    /** Post-firma en PAdES un documento PDF a partir de una pre-firma y la firma PKCS#1, generando un PDF final completo.
     * @param digestAlgorithmName Nombre del algoritmo de huella digital usado para la firma (debe ser el mismo que el usado en la pre-firma).
     * <p>Se aceptan los siguientes algoritmos en el par&aacute;metro <code>digestAlgorithmName</code>:</p>
     * <ul>
     *  <li><i>SHA1</i></li>
     *  <li><i>MD5</i> (no recomendado por vulnerable)</li>
     *  <li><i>MD2</i> (no recomendado por vulnerable)</li>
     *  <li><i>SHA-256</i></li>
     *  <li><i>SHA-384</i></li>
     *  <li><i>SHA-512</i></li>
     * </ul>
     * @param inPdf PDF a firmar (debe ser el mismo que el usado en la pre-firma).
     * @param signerCertificateChain Cadena de certificados del firmante (debe ser la misma que la usado en la pre-firma).
     * @param pkcs1Signature Resultado de la firma PKCS#1 v1.5 de los datos de la pre-firma.
     * @param preSign Resultado de la pre-firma
     * @param enhancer Manejador para la generaci&oacute;n de nuevos modos de firma (con
     * sello de tiempo, archivo longevo, etc.)
     * @param enhancerConfig Configuraci&oacute;n para generar el nuevo modo de firma.
     * @return PDF firmado
     * @throws AOException en caso de cualquier tipo de error
     * @throws IOException Cuando ocurre algun error en la conversi&oacute;n o generaci&oacute;n
     *                     de estructuras.
     * @throws NoSuchAlgorithmException Si hay problemas con el algoritmo durante el sello de tiempo. */
    public static byte[] postSign(final String digestAlgorithmName,
                                  final byte[] inPdf,
                                  final Certificate[] signerCertificateChain,
                                  final byte[] pkcs1Signature,
                                  final PdfSignResult preSign,
                                  final SignEnhancer enhancer,
                                  final Properties enhancerConfig) throws AOException,
                                                                          IOException,
                                                                          NoSuchAlgorithmException {
    	// Obtenemos la firma
    	final PdfSignResult completePdfSSignature = generatePdfSignature(
    		digestAlgorithmName,
    		signerCertificateChain,
    		preSign.getExtraParams(),
    		pkcs1Signature,
    		preSign.getSign(),
    		preSign.getFileID(),
    		preSign.getTimestamp(),
    		preSign.getSignTime(),
    		enhancer,
    		enhancerConfig
		);

        // Insertamos la firma en el PDF
    	return insertSignatureOnPdf(
    		inPdf,
    		signerCertificateChain,
    		completePdfSSignature
		);
    }

    private static PdfSignResult generatePdfSignature(final String digestAlgorithmName,
                                                      final Certificate[] signerCertificateChain,
                                                      final Properties xParams,
                                                      final byte[] pkcs1Signature,
                                                      final byte[] signedAttributes,
                                                      final String pdfFileId,
                                                      final byte[] timestamp,
                                                      final GregorianCalendar signingTime,
                                                      final SignEnhancer enhancer,
                                                      final Properties enhancerConfig) throws AOException,
                                                                                              IOException,
                                                                                              NoSuchAlgorithmException {
        byte[] completeCAdESSignature = CAdESTriPhaseSigner.postSign(
    		AOSignConstants.getDigestAlgorithmName(digestAlgorithmName),
    		null,
    		signerCertificateChain,
    		pkcs1Signature,
    		signedAttributes
		);

        final Properties extraParams = xParams != null ? xParams : new Properties();

        //**************************************************
        //***************** SELLO DE TIEMPO ****************

        // El sello a nivel de firma nunca se aplica si han pedido solo sello a nivel de documento
        if (!TsaParams.TS_DOC.equals(extraParams.getProperty("tsType"))) { //$NON-NLS-1$
	        TsaParams tsaParams;
	        try {
	        	tsaParams = new TsaParams(extraParams);
	        }
	        catch(final Exception e) {
	        	tsaParams = null;
        }
	        if (tsaParams != null) {
	        	completeCAdESSignature = new CMSTimestamper(tsaParams).addTimestamp(
					completeCAdESSignature,
					tsaParams.getTsaHashAlgorithm(),
					signingTime
				);
	        }
        }

        //************** FIN SELLO DE TIEMPO ****************
        //***************************************************

        if (enhancer != null) {
        	completeCAdESSignature = enhancer.enhance(completeCAdESSignature, enhancerConfig);
        }

        return new PdfSignResult(
    		pdfFileId,
    		completeCAdESSignature,
    		timestamp, // Sello de tiempo
    		signingTime,
    		xParams != null ? xParams : new Properties());
    }

    private static byte[] insertSignatureOnPdf(final byte[] inPdf,
    		                                   final Certificate[] signerCertificateChain,
    		                                   final PdfSignResult signature) throws AOException, IOException {
        final byte[] outc = new byte[CSIZE];

        if (signature.getSign().length > CSIZE) {
        	throw new AOException(
    			"El tamano de la firma (" + signature.getSign().length + ") supera el maximo permitido para un PDF (" + CSIZE + ")" //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			);
        }

        final PdfDictionary dic2 = new PdfDictionary();
        System.arraycopy(signature.getSign(), 0, outc, 0, signature.getSign().length);
        dic2.put(PdfName.CONTENTS, new PdfString(outc).setHexWriting(true));

        final PdfTriPhaseSession pts;
		try {
			pts = PdfSessionManager.getSessionData(inPdf, signerCertificateChain, signature.getSignTime(), signature.getExtraParams());
		}
		catch (final DocumentException e1) {
			throw new IOException(e1);
		}
        final PdfSignatureAppearance sap = pts.getSAP();

    	final ByteArrayOutputStream baos = pts.getBAOS();
	    final String badFileID = pts.getFileID();

	    try {
	       sap.close(dic2);
	    }
	    catch (final Exception e) {
	    	baos.close();
	        throw new AOException("Error al cerrar el PDF para finalizar el proceso de firma", e); //$NON-NLS-1$
	    }

	    final byte[] ret = new String(baos.toByteArray(), "ISO-8859-1").replace(badFileID, signature.getFileID()).getBytes("ISO-8859-1"); //$NON-NLS-1$ //$NON-NLS-2$

	    baos.close();

	    return ret;
    }

}
