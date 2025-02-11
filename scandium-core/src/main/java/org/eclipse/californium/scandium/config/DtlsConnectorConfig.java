/*******************************************************************************
 * Copyright (c) 2015 - 2019 Bosch Software Innovations GmbH and others.
 * 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * and Eclipse Distribution License v1.0 which accompany this distribution.
 * 
 * The Eclipse Public License is available at
 *    http://www.eclipse.org/legal/epl-v20.html
 * and the Eclipse Distribution License is available at
 *    http://www.eclipse.org/org/documents/edl-v10.html.
 * 
 * Contributors:
 *    Kai Hudalla (Bosch Software Innovations GmbH) - re-factor DTLSConnectorConfig into
 *                                               an immutable, provide a "builder" for easier
 *                                               instantiation/configuration
 *    Kai Hudalla (Bosch Software Innovations GmbH) - add support for anonymous client-only
 *                                               configuration
 *    Kai Hudalla (Bosch Software Innovations GmbH) - fix bug 483559
 *    Achim Kraus (Bosch Software Innovations GmbH) - add enable address reuse
 *    Ludwig Seitz (RISE SICS) - Added support for raw public key validation
 *    Achim Kraus (Bosch Software Innovations GmbH) - include trustedRPKs in
 *                                                    determineCipherSuitesFromConfig
 *    Achim Kraus (Bosch Software Innovations GmbH) - add automatic resumption
 *    Achim Kraus (Bosch Software Innovations GmbH) - issue #549
 *                                                    trustStore := null, disable x.509
 *                                                    trustStore := [], enable x.509, trust all
 *    Bosch Software Innovations GmbH - remove serverNameResolver property
 *    Vikram (University of Rostock) - added CipherSuite TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256
 *    Achim Kraus (Bosch Software Innovations GmbH) - add multiple receiver threads.
 *                                                    move default thread numbers to this configuration.
 *    Achim Kraus (Bosch Software Innovations GmbH) - add deferred processed messages
 *    Achim Kraus (Bosch Software Innovations GmbH) - add server only.
 *******************************************************************************/

package org.eclipse.californium.scandium.config;

import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.californium.elements.DtlsEndpointContext;
import org.eclipse.californium.elements.config.BasicDefinition;
import org.eclipse.californium.elements.config.CertificateAuthenticationMode;
import org.eclipse.californium.elements.config.Configuration;
import org.eclipse.californium.elements.config.SystemConfig;
import org.eclipse.californium.elements.config.TimeDefinition;
import org.eclipse.californium.elements.config.EnumListDefinition;
import org.eclipse.californium.elements.util.StringUtil;
import org.eclipse.californium.scandium.ConnectionListener;
import org.eclipse.californium.scandium.DTLSConnector;
import org.eclipse.californium.scandium.DtlsHealth;
import org.eclipse.californium.scandium.auth.ApplicationLevelInfoSupplier;
import org.eclipse.californium.scandium.config.DtlsConfig.DtlsRole;
import org.eclipse.californium.scandium.dtls.CertificateMessage;
import org.eclipse.californium.scandium.dtls.CertificateRequest;
import org.eclipse.californium.scandium.dtls.CertificateType;
import org.eclipse.californium.scandium.dtls.ConnectionIdExtension;
import org.eclipse.californium.scandium.dtls.ConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.ExtendedMasterSecretMode;
import org.eclipse.californium.scandium.dtls.HelloVerifyRequest;
import org.eclipse.californium.scandium.dtls.InMemoryConnectionStore;
import org.eclipse.californium.scandium.dtls.MultiNodeConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.MaxFragmentLengthExtension.Length;
import org.eclipse.californium.scandium.dtls.ProtocolVersion;
import org.eclipse.californium.scandium.dtls.ResumptionSupportingConnectionStore;
import org.eclipse.californium.scandium.dtls.SessionStore;
import org.eclipse.californium.scandium.dtls.SignatureAndHashAlgorithm;
import org.eclipse.californium.scandium.dtls.SingleNodeConnectionIdGenerator;
import org.eclipse.californium.scandium.dtls.HelloExtension.ExtensionType;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuite.KeyExchangeAlgorithm;
import org.eclipse.californium.scandium.dtls.cipher.CipherSuiteSelector;
import org.eclipse.californium.scandium.dtls.cipher.DefaultCipherSuiteSelector;
import org.eclipse.californium.scandium.dtls.cipher.XECDHECryptography.SupportedGroup;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedMultiPskStore;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedPskStore;
import org.eclipse.californium.scandium.dtls.pskstore.AdvancedSinglePskStore;
import org.eclipse.californium.scandium.dtls.resumption.ConnectionStoreResumptionVerifier;
import org.eclipse.californium.scandium.dtls.resumption.ResumptionVerifier;
import org.eclipse.californium.scandium.dtls.x509.CertificateConfigurationHelper;
import org.eclipse.californium.scandium.dtls.x509.CertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.ConfigurationHelperSetup;
import org.eclipse.californium.scandium.dtls.x509.KeyManagerCertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.NewAdvancedCertificateVerifier;
import org.eclipse.californium.scandium.dtls.x509.SingleCertificateProvider;
import org.eclipse.californium.scandium.dtls.x509.StaticNewAdvancedCertificateVerifier;
import org.eclipse.californium.scandium.util.ListUtils;

/**
 * A container for all configuration options of a {@link DTLSConnector}.
 * <p>
 * Instances of this class are immutable and can only be created by means of the
 * {@link Builder}, e.g.
 * </p>
 * 
 * <pre>
 * InetSocketAddress bindToAddress = new InetSocketAddress(0); // use ephemeral port
 * DtlsConnectorConfig config = DtlsConnectorConfig.builder()
 *    .setAddress(bindToAddress)
 *    .setAdvancedPskStore(new AdvancedSinglePskStore("identity", "secret".getBytes()))
 *    .set... // additional configuration
 *    .build();
 * 
 * DTLSConnector connector = new DTLSConnector(config);
 * connector.start();
 * ...
 * </pre>
 * 
 * Since 3.0 many values are now backed-up in {@link Configuration} using
 * {@link DtlsConfig}. The {@link Builder} offers also the setter for
 * {@link Configuration} definitions. In order not to mix up a provided
 * {@link Configuration}, that gets cloned on creating the {@link Builder}.
 * 
 * Generally the not provided configuration values will be filled in using
 * proper values for the already provided ones. E.g. if the
 * {@link Builder#setAdvancedPskStore(AdvancedPskStore)} is used, but no
 * explicit cipher suite is set with
 * {@link Builder#setSupportedCipherSuites(CipherSuite...)}, the configuration
 * chose some PSK cipher suites on its own. For the asymmetric cryptography
 * functions, the estimation of the proper signature and hash algorithms and the
 * supported curves for ECDSA/ECDHE is more complicated. Therefore this is
 * implemented in the {@link CertificateConfigurationHelper}, see there for
 * details.
 */
public final class DtlsConnectorConfig {

	/**
	 * Local network interface.
	 */
	private InetSocketAddress address;
	/**
	 * Advanced certificate verifier for non-blocking dynamic trust.
	 * 
	 * @since 2.5
	 */
	private NewAdvancedCertificateVerifier advancedCertificateVerifier;

	private Configuration configuration;

	/**
	 * Enable to reuse the address.
	 */
	private Boolean useReuseAddress;

	/**
	 * Protocol version to use for sending a hello verify request. Default
	 * {@code null} to reply the clients version.
	 * 
	 * @since 2.5
	 */
	private ProtocolVersion protocolVersionForHelloVerifyRequests;

	/**
	 * Advanced store of PSK credentials.
	 * 
	 * @since 2.3
	 */
	private AdvancedPskStore advancedPskStore;

	/**
	 * The certificate identity provider.
	 * 
	 * @since 3.0
	 */
	private CertificateProvider certificateIdentityProvider;
	/**
	 * The certificate configuration helper.
	 * 
	 * @since 3.0
	 */
	private CertificateConfigurationHelper certificateConfigurationHelper;
	/**
	 * Cipher suite selector.
	 * 
	 * @since 2.3
	 */
	private CipherSuiteSelector cipherSuiteSelector;

	/** the supported cipher suites in order of preference */
	private List<CipherSuite> supportedCipherSuites;

	/**
	 * the supported signature and hash algorithms in order of preference.
	 * 
	 * @since 2.3
	 */
	private List<SignatureAndHashAlgorithm> supportedSignatureAlgorithms;

	/**
	 * the supported groups (curves) in order of preference.
	 * 
	 * @since 2.3
	 */
	private List<SupportedGroup> supportedGroups;

	/**
	 * Logging tag.
	 * 
	 * Tag logging messages, if multiple connectors share the same logging
	 * instance.
	 */
	private String loggingTag;

	/**
	 * Connection id generator. {@code null}, if connection id is not supported.
	 * The generator may only support the use of a connection id without using
	 * it by itself. In that case
	 * {@link ConnectionIdGenerator#useConnectionId()} will return
	 * {@code false}.
	 */
	private ConnectionIdGenerator connectionIdGenerator;

	private ApplicationLevelInfoSupplier applicationLevelInfoSupplier;

	/**
	 * Connection Listener.
	 */
	private ConnectionListener connectionListener;

	/**
	 * Session store for {@link InMemoryConnectionStore}.
	 * 
	 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
	 * session store must be provided directly to that implementation. In that
	 * case, the configured session store here will be ignored.
	 * 
	 * @see DTLSConnector#createConnectionStore
	 * @since 3.0
	 */
	private SessionStore sessionStore;

	/**
	 * Server side verifier for DTLS session resumption.
	 * 
	 * Supports none-blocking processing.
	 * 
	 * @since 3.0
	 */
	private ResumptionVerifier resumptionVerifier;

	private DtlsHealth healthHandler;

	/**
	 * Creates a new instance for configuration options for a
	 * {@code DTLSConnector} instance.
	 * 
	 * @param configuration the configuration with {@link DtlsConfig}
	 *            definitions.
	 * @throws NullPointerException if configuration is {@code null}
	 * @since 3.0
	 */
	private DtlsConnectorConfig(Configuration configuration) {
		if (configuration == null) {
			throw new NullPointerException("Configuration must not be null!");
		}
		this.configuration = new Configuration(configuration);
	}

	/**
	 * Gets configuration with {@link DtlsConfig} definitions.
	 * 
	 * @return configuration with {@link DtlsConfig} definitions
	 * @since 3.0
	 */
	public Configuration getConfiguration() {
		return configuration;
	}

	/**
	 * Get protocol version for hello verify requests to send.
	 * 
	 * Before version 2.5.0, Californium used fixed the protocol version DTLS
	 * 1.2 to send the HelloVerifyRequest. According
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>, that
	 * HelloVerifyRequest SHOULD be sent using protocol version DTLS 1.0. But
	 * that found to be ambiguous, because it's also requested that "The server
	 * MUST use the same version number in the HelloVerifyRequest that it would
	 * use when sending a ServerHello." With that, Californium from 2.6.0 on
	 * will, by default, reply the version the client sent in the
	 * HelloVerifyRequest, and will postpone the version negotiation until the
	 * client has verified it's endpoint ownership. If that client version is
	 * below DTLS 1.0, a DTLS 1.0 will be used. If a different behavior is
	 * wanted, you may use the related setter to provide a fixed version for the
	 * HelloVerifyRequest. In order to provide backwards compatibility to
	 * version before 2.5.0 , configure to use protocol version DTLS 1.2.
	 * 
	 * @return fixed protocol version, or {@code null}, to reply the clients
	 *         version. Default is {@code null}.
	 * @see HelloVerifyRequest
	 * @see Builder#setProtocolVersionForHelloVerifyRequests(ProtocolVersion)
	 * @since 2.5
	 */
	public ProtocolVersion getProtocolVersionForHelloVerifyRequests() {
		return protocolVersionForHelloVerifyRequests;
	}

	/**
	 * Gets record size limit.
	 * 
	 * Included in the CLIENT_HELLO and SERVER_HELLO to negotiate the record
	 * size limit.
	 * 
	 * @return record size limit, or {@code null}, if not used.
	 * @see DtlsConfig#DTLS_RECORD_SIZE_LIMIT
	 * @since 2.4
	 */
	public Integer getRecordSizeLimit() {
		return configuration.get(DtlsConfig.DTLS_RECORD_SIZE_LIMIT);
	}

	/**
	 * Gets the maximum amount of message payload data that this connector can
	 * receive in a single DTLS record.
	 * <p>
	 * The code returned is either {@code null} or one of the following:
	 * <ul>
	 * <li>1 - 2^9 bytes</li>
	 * <li>2 - 2^10 bytes</li>
	 * <li>3 - 2^11 bytes</li>
	 * <li>4 - 2^12 bytes</li>
	 * </ul>
	 * 
	 * @return the code indicating the maximum payload length, or {@code null}.
	 * @see DtlsConfig#DTLS_MAX_FRAGMENT_LENGTH
	 * @since 3.0 (changed name and return type)
	 */
	public Length getMaxFragmentLength() {
		return configuration.get(DtlsConfig.DTLS_MAX_FRAGMENT_LENGTH);
	}

	/**
	 * Gets the maximum length of a single reassembled fragmented handshake
	 * message.
	 * 
	 * @return maximum length, or {@code null}.
	 * @see DtlsConfig#DTLS_MAX_FRAGMENTED_HANDSHAKE_MESSAGE_LENGTH
	 */
	public Integer getMaxFragmentedHandshakeMessageLength() {
		return configuration.get(DtlsConfig.DTLS_MAX_FRAGMENTED_HANDSHAKE_MESSAGE_LENGTH);
	}

	/**
	 * Gets enable to use UDP messages with multiple dtls records.
	 * 
	 * Default behavior enables the usage of multiple records, but disables it
	 * as back off after two retransmissions.
	 * 
	 * @return {@code true}, if enabled, {@code false}, otherwise. {@code null}
	 *         for default behavior.
	 * @see DtlsConfig#DTLS_USE_MULTI_RECORD_MESSAGES
	 * @since 2.4
	 */
	public Boolean useMultiRecordMessages() {
		return configuration.get(DtlsConfig.DTLS_USE_MULTI_RECORD_MESSAGES);
	}

	/**
	 * Enable to use dtls records with multiple handshake messages.
	 * 
	 * Default behavior disables the usage on the server side, and enables the
	 * usage of multiple handshake messages on the client side, if the server
	 * send such dtls records.
	 * 
	 * @return {@code true}, if enabled, {@code false}, otherwise. {@code null}
	 *         for default behavior.
	 * @see DtlsConfig#DTLS_USE_MULTI_HANDSHAKE_MESSAGE_RECORDS
	 * @since 2.4
	 */
	public Boolean useMultiHandshakeMessageRecords() {
		return configuration.get(DtlsConfig.DTLS_USE_MULTI_HANDSHAKE_MESSAGE_RECORDS);
	}

	/**
	 * Gets the (initial) time to wait before a handshake flight of messages
	 * gets re-transmitted.
	 * 
	 * This timeout gets adjusted during the course of repeated re-transmission
	 * of a flight. The DTLS spec suggests an exponential back-off strategy,
	 * i.e. after each re-transmission the timeout value is doubled.
	 * 
	 * @return the (initial) time to wait in milliseconds
	 * @see DtlsConfig#DTLS_RETRANSMISSION_TIMEOUT
	 */
	public int getRetransmissionTimeout() {
		return configuration.getTimeAsInt(DtlsConfig.DTLS_RETRANSMISSION_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	/**
	 * Gets the maximum time to wait before a handshake flight of messages gets
	 * re-transmitted.
	 * 
	 * @return the maximum time to wait in milliseconds
	 * @see DtlsConfig#DTLS_MAX_RETRANSMISSION_TIMEOUT
	 * @since 3.0
	 */
	public Integer getMaxRetransmissionTimeout() {
		return configuration.getTimeAsInt(DtlsConfig.DTLS_MAX_RETRANSMISSION_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	/**
	 * Gets the random factor for the initial retransmission timeout.
	 * 
	 * @return the random factor for the initial retransmission timeout. Values
	 *         range [1.0 - 2.0]
	 * @see DtlsConfig#DTLS_RETRANSMISSION_INIT_RANDOM
	 * @since 3.0
	 */
	public Float getRetransmissionRandomFactor() {
		return configuration.get(DtlsConfig.DTLS_RETRANSMISSION_INIT_RANDOM);
	}

	/**
	 * Gets the scale factor for retransmission timeouts back-off.
	 * 
	 * @return the scale factor for retransmission timeout. Values range [1.0 -
	 *         2.0]
	 * @see DtlsConfig#DTLS_RETRANSMISSION_TIMEOUT_SCALE
	 * @since 3.0
	 */
	public Float getRetransmissionTimeoutScale() {
		return configuration.get(DtlsConfig.DTLS_RETRANSMISSION_TIMEOUT_SCALE);
	}

	/**
	 * Gets the additional (initial) time to wait before a handshake flight of
	 * messages gets re-transmitted, when the other peer is expected to perform
	 * ECC calculations.
	 * 
	 * ECC calculations may be time intensive, especially for smaller
	 * micro-controllers without ecc-hardware support. The additional timeout
	 * prevents Californium from resending a flight too early. The extra time is
	 * used for the DTLS-client, if a ECDSA or ECDHE cipher suite is proposed,
	 * and for the DTLS-server, if a ECDSA or ECDHE cipher suite is selected.
	 * 
	 * This timeout is added to {@link #getRetransmissionTimeout()} and on each
	 * retransmission, the resulting time is doubled.
	 * 
	 * @return the additional (initial) time to wait in milliseconds. Default is
	 *         {@link DtlsConfig#DEFAULT_ADDITIONAL_TIMEOUT_FOR_ECC_IN_MILLISECONDS}.
	 * @see DtlsConfig#DTLS_ADDITIONAL_ECC_TIMEOUT
	 * @since 3.0
	 */
	public int getAdditionalTimeoutForEcc() {
		return configuration.getTimeAsInt(DtlsConfig.DTLS_ADDITIONAL_ECC_TIMEOUT, TimeUnit.MILLISECONDS);
	}

	/**
	 * Number of retransmissions before the attempt to transmit a flight in
	 * back-off mode.
	 * 
	 * <a href="https://tools.ietf.org/html/rfc6347#page-12" target="_blank">
	 * RFC 6347, Section 4.1.1.1, Page 12</a>
	 * 
	 * In back-off mode, UDP datagrams of maximum 512 bytes, or the negotiated
	 * records size, if that is smaller, are used. Each handshake message is
	 * placed in one dtls record, or more dtls records, if the handshake message
	 * is too large and must be fragmented. Beside of the CCS and FINISH dtls
	 * records, which send together in one UDP datagram, all other records are
	 * send in separate datagrams.
	 * 
	 * The {@link #useMultiHandshakeMessageRecords()} and
	 * {@link #useMultiRecordMessages()} has precedence over the back-off
	 * definition.
	 * 
	 * Value {@code 0}, to disable it, default is value
	 * {@link #getMaxRetransmissions()} / 2.
	 * 
	 * @return the number of re-transmissions to use the back-off mode
	 * @see DtlsConfig#DTLS_RETRANSMISSION_BACKOFF
	 * @since 2.4
	 */
	public Integer getBackOffRetransmission() {
		Integer backoff = configuration.get(DtlsConfig.DTLS_RETRANSMISSION_BACKOFF);
		if (backoff == null) {
			backoff = getMaxRetransmissions() / 2;
		}
		return backoff;
	}

	/**
	 * Gets the maximum number of times a flight of handshake messages gets
	 * re-transmitted to a peer.
	 * 
	 * @return the maximum number of re-transmissions
	 * @see DtlsConfig#DTLS_MAX_RETRANSMISSIONS
	 */
	public Integer getMaxRetransmissions() {
		return configuration.get(DtlsConfig.DTLS_MAX_RETRANSMISSIONS);
	}

	/**
	 * Gets the maximum number of deferred processed outgoing application data
	 * messages.
	 * 
	 * @return the maximum number of deferred processed outgoing application
	 *         data messages
	 * @see DtlsConfig#DTLS_MAX_DEFERRED_OUTBOUND_APPLICATION_MESSAGES
	 */
	public Integer getMaxDeferredProcessedOutgoingApplicationDataMessages() {
		return configuration.get(DtlsConfig.DTLS_MAX_DEFERRED_OUTBOUND_APPLICATION_MESSAGES);
	}

	/**
	 * Gets the maximum size of all deferred processed incoming records.
	 * 
	 * @return the maximum size of all deferred processed incoming records
	 * @see DtlsConfig#DTLS_MAX_DEFERRED_INBOUND_RECORDS_SIZE
	 */
	public Integer getMaxDeferredProcessedIncomingRecordsSize() {
		return configuration.get(DtlsConfig.DTLS_MAX_DEFERRED_INBOUND_RECORDS_SIZE);
	}

	/**
	 * Gets the maximum transmission unit.
	 * 
	 * Maximum number of bytes sent in one transmission.
	 * 
	 * @return maximum transmission unit
	 * @see DtlsConfig#DTLS_MAX_TRANSMISSION_UNIT
	 */
	public Integer getMaxTransmissionUnit() {
		return configuration.get(DtlsConfig.DTLS_MAX_TRANSMISSION_UNIT);
	}

	/**
	 * Gets the maximum transmission unit limit for auto detection.
	 * 
	 * Limit Maximum number of bytes sent in one transmission.
	 * 
	 * @return maximum transmission unit limit. Default
	 *         {@link DtlsConfig#DEFAULT_MAX_TRANSMISSION_UNIT_LIMIT}.
	 * @see DtlsConfig#DTLS_MAX_TRANSMISSION_UNIT_LIMIT
	 * @since 2.3
	 */
	public Integer getMaxTransmissionUnitLimit() {
		return configuration.get(DtlsConfig.DTLS_MAX_TRANSMISSION_UNIT_LIMIT);
	}

	/**
	 * Enable to stop retransmission early.
	 * 
	 * @return {@code true} if retransmissions should be stopped as soon as we
	 *         receive handshake message
	 * @see DtlsConfig#DTLS_USE_EARLY_STOP_RETRANSMISSION
	 * @since 3.0 (renamed, was isEarlyStopRetransmission)
	 */
	public Boolean useEarlyStopRetransmission() {
		return configuration.get(DtlsConfig.DTLS_USE_EARLY_STOP_RETRANSMISSION);
	}

	/**
	 * Enable address to be reusable.
	 * 
	 * Mainly used for unit tests.
	 * 
	 * @return {@code true}, if address reuse should be enabled for the socket.
	 * @see DatagramSocket#setReuseAddress(boolean)
	 * @see Builder#setReuseAddress(boolean)
	 * @since 3.0 (renamed was isAddressReuseEnabled)
	 */
	public Boolean useReuseAddress() {
		return useReuseAddress;
	}

	/**
	 * Checks whether the connector should support the use of the TLS
	 * <a href="https://tools.ietf.org/html/rfc6066#section-3" target="_blank">
	 * Server Name Indication extension</a> in the DTLS handshake.
	 * <p>
	 * If enabled, the client side should send a server name extension, if the
	 * server is specified with hostname rather then with a raw ip-address. The
	 * server side support currently includes a server name specific PSK secret
	 * lookup and a forwarding of the server name to the CoAP stack in the
	 * {@link DtlsEndpointContext}. The x509 or RPK credentials lookup is
	 * currently not server name specific, therefore the server's certificate
	 * will be the same, regardless of the indicated server name.
	 * <p>
	 * The default value of this property is {@code null}. If this property is
	 * not set explicitly using {@link Builder#setReuseAddress(boolean)}, then
	 * the {@link Builder#build()} method will set it to {@code false}.
	 * 
	 * @return {@code true}, if SNI should be used.
	 * @see DtlsConfig#DTLS_USE_SERVER_NAME_INDICATION
	 * @since 3.0 (renamed, was isSniEnabled)
	 */
	public Boolean useServerNameIndication() {
		return configuration.get(DtlsConfig.DTLS_USE_SERVER_NAME_INDICATION);
	}

	/**
	 * Gets the <em>Extended Master Secret</em> TLS extension mode.
	 * 
	 * <p>
	 * See <a href="https://tools.ietf.org/html/rfc7627" target="_blank">
	 * RFC7627, Extended Master Secret extension</a> and
	 * {@link ExtendedMasterSecretMode} for details.
	 * </p>
	 * 
	 * @return the extended master secret mode.
	 * @see DtlsConfig#DTLS_EXTENDED_MASTER_SECRET_MODE
	 * @since 3.0
	 */
	public ExtendedMasterSecretMode getExtendedMasterSecretMode() {
		return configuration.get(DtlsConfig.DTLS_EXTENDED_MASTER_SECRET_MODE);
	}

	/**
	 * Threshold to use a HELLO_VERIFY_REQUEST also for session resumption in
	 * percent of {@link #getMaxConnections()}. Though a CLIENT_HELLO with an
	 * session id is used in session resumption, that session ID could be used
	 * as weaker verification, that the peer controls the source address.
	 * 
	 * <pre>
	 * Value 
	 * 0 : always use a verify request.
	 * 1 ... 100 : dynamically use a verify request.
	 * </pre>
	 * 
	 * Peers are identified by their endpoint (ip-address and port). To protect
	 * the server from congestion by address spoofing, a HELLO_VERIFY_REQUEST is
	 * used. That adds one exchange and with that, additional latency. In cases
	 * of session resumption, the server may also use the dtls session ID as a
	 * weaker proof of a valid client. Unfortunately there are several
	 * elaborated attacks to that (e.g. on-path-attacker may alter the
	 * source-address). To mitigate this vulnerability, this threshold defines a
	 * maximum percentage of handshakes without HELLO_VERIFY_REQUEST. If more
	 * resumption handshakes without verified peers are pending than this
	 * threshold, then a HELLO_VERIFY_REQUEST is used again. Additionally, if a
	 * peer resumes a session (by id), but a different session is related to its
	 * endpoint, then a verify request is used to ensure, that the peer really
	 * owns that endpoint.
	 * <p>
	 * <b>Note:</b> a value larger than 0 will call the
	 * {@link ResumptionVerifier}. If that implementation is expensive, please
	 * ensure, that this value is configured with {@code 0}. Otherwise,
	 * CLIENT_HELLOs with invalid session IDs may be spoofed and gets too
	 * expensive.
	 * </p>
	 * <p>
	 * <b>Note:</b> if spoofing is considered to be relevant for the used
	 * network environment, please set this to {@code 0} using
	 * {@link Builder#set} with
	 * {@link DtlsConfig#DTLS_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD} in order to
	 * disable this function.
	 * </p>
	 * 
	 * @return threshold handshakes without verified peer in percent of
	 *         {@link #getMaxConnections()}.
	 * @see HelloVerifyRequest
	 * @see DtlsConfig#DTLS_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD
	 */
	public Integer getVerifyPeersOnResumptionThreshold() {
		return configuration.get(DtlsConfig.DTLS_VERIFY_PEERS_ON_RESUMPTION_THRESHOLD);
	}

	/**
	 * Enable/disable the server's HELLO_VERIFY_REQUEST, if peers shares at
	 * least one PSK based cipher suite.
	 * <p>
	 * <b>Note:</b> it is not recommended to disable the HELLO_VERIFY_REQUEST!
	 * See <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * To limit the amplification, the peers must share PSK cipher suites to by
	 * pass that check. If only certificate based cipher suites are shared, the
	 * HELLO_VERIFY_REQUEST will still be used.
	 * 
	 * @return {@code true}, if a HELLO_VERIFY_REQUEST should be send to the
	 *         client, {@code false}, if no HELLO_VERIFY_REQUEST is used.
	 * @see HelloVerifyRequest
	 * @see #useHelloVerifyRequest()
	 * @see DtlsConfig#DTLS_USE_HELLO_VERIFY_REQUEST_FOR_PSK
	 * @since 3.0
	 */
	public Boolean useHelloVerifyRequestForPsk() {
		return configuration.get(DtlsConfig.DTLS_USE_HELLO_VERIFY_REQUEST_FOR_PSK);
	}

	/**
	 * Generally enable/disable the server's HELLO_VERIFY_REQUEST.
	 * <p>
	 * <b>Note:</b> it is strongly not recommended to disable the
	 * HELLO_VERIFY_REQUEST for certificates! That creates a large
	 * amplification! See
	 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
	 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>.
	 * </p>
	 * 
	 * @return {@code true}, if a HELLO_VERIFY_REQUEST should be send to the
	 *         client, {@code false}, if no HELLO_VERIFY_REQUEST is used.
	 * @see HelloVerifyRequest
	 * @see #useHelloVerifyRequestForPsk()
	 * @see DtlsConfig#DTLS_USE_HELLO_VERIFY_REQUEST
	 * @since 3.0
	 */
	public Boolean useHelloVerifyRequest() {
		return configuration.get(DtlsConfig.DTLS_USE_HELLO_VERIFY_REQUEST);
	}

	/**
	 * Gets connection ID generator.
	 * 
	 * If no connection ID generator is provided via
	 * {@link Builder#setConnectionIdGenerator(ConnectionIdGenerator)}, the
	 * value of {@link DtlsConfig#DTLS_CONNECTION_ID_LENGTH} is used to create a
	 * {@link SingleNodeConnectionIdGenerator}, if set. If additionally
	 * {@link DtlsConfig#DTLS_CONNECTION_ID_NODE_ID} is available, a
	 * {@link MultiNodeConnectionIdGenerator} is created, but requires a CID
	 * length of at least 5 bytes throwing {@link IllegalStateException} on
	 * less.
	 * 
	 * @return connection id generator. {@code null} for not supported. The
	 *         returned generator may only support the use of a connection id
	 *         without using it by itself. In that case
	 *         {@link ConnectionIdGenerator#useConnectionId()} will return
	 *         {@code false}.
	 * @see Builder#setConnectionIdGenerator(ConnectionIdGenerator)
	 * @see DtlsConfig#DTLS_CONNECTION_ID_LENGTH
	 * @see DtlsConfig#DTLS_CONNECTION_ID_NODE_ID
	 */
	public ConnectionIdGenerator getConnectionIdGenerator() {
		return connectionIdGenerator;
	}

	/**
	 * Enable/disable the use of the deprecated CID before version 9 of <a href=
	 * "https://datatracker.ietf.org/doc/draft-ietf-tls-dtls-connection-id/"
	 * target="_blank">Draft dtls-connection-id</a> for the client.
	 * 
	 * @return cid extension code point to use along with the deprecated MAC
	 *         calculation, or, {@code null}, if final extension code point 54
	 *         and MAC calculation should be used otherwise.
	 * @see ConnectionIdExtension
	 * @see DtlsConfig#DTLS_USE_DEPRECATED_CID
	 * @since 3.0
	 */
	public Integer useDeprecatedCid() {
		return configuration.get(DtlsConfig.DTLS_USE_DEPRECATED_CID);
	}

	/**
	 * Enable/disable the support for the deprecated CID before version 9 of
	 * <a href=
	 * "https://datatracker.ietf.org/doc/draft-ietf-tls-dtls-connection-id/"
	 * target="_blank">Draft dtls-connection-id</a> for the server.
	 * 
	 * If enabled, the server will also accept the deprecated value {@code 53}
	 * for the connection id extension and use the old MAC definition, if so.
	 * 
	 * @return {@code true}, for support the deprecated extension ID 53 along
	 *         with the deprecated MAC calculation, {@code false}, otherwise.
	 * @see ConnectionIdExtension
	 * @see DtlsConfig#DTLS_SUPPORT_DEPRECATED_CID
	 * @since 3.0
	 */
	public Boolean supportsDeprecatedCid() {
		return configuration.get(DtlsConfig.DTLS_SUPPORT_DEPRECATED_CID);
	}

	/**
	 * Gets the number of outbound messages that can be buffered in memory
	 * before messages are dropped.
	 * 
	 * @return the number of messages
	 * @see DtlsConfig#DTLS_OUTBOUND_MESSAGE_BUFFER_SIZE
	 */
	public Integer getOutboundMessageBufferSize() {
		return configuration.get(DtlsConfig.DTLS_OUTBOUND_MESSAGE_BUFFER_SIZE);
	}

	/**
	 * Gets the IP address and port the connector is bound to.
	 * 
	 * @return the address
	 * @see Builder#setAddress(InetSocketAddress)
	 */
	public InetSocketAddress getAddress() {
		return address;
	}

	/**
	 * Gets the certificate identity provider.
	 * 
	 * @return the certificate identity provider, or {@code null}, if the
	 *         connector is not supposed to support certificate based
	 *         authentication
	 * @see Builder#setCertificateIdentityProvider(CertificateProvider)
	 * @see KeyManagerCertificateProvider
	 * @see SingleCertificateProvider
	 * @since 3.0
	 */
	public CertificateProvider getCertificateIdentityProvider() {
		return certificateIdentityProvider;
	}

	/**
	 * Get cipher suite selector for the server side.
	 * 
	 * @return cipher suite selector. Default
	 *         {@link DefaultCipherSuiteSelector}.
	 * @see Builder#setCipherSuiteSelector(CipherSuiteSelector)
	 * @since 2.3
	 */
	public CipherSuiteSelector getCipherSuiteSelector() {
		return cipherSuiteSelector;
	}

	/**
	 * Gets the preselected cipher suites.
	 * 
	 * If no supported cipher suites are provided via
	 * {@link Builder#setSupportedCipherSuites} or
	 * {@link DtlsConfig#DTLS_CIPHER_SUITES}, consider only this subset of
	 * {@link CipherSuite} to be automatically selected as supported cipher
	 * suites depending on other setting (e.g. if settings allow only PSK, only
	 * PSK compatible cipher suite from this list will be selected).
	 * 
	 * Not used, if supported cipher suites are provided.
	 * 
	 * @return the preselected cipher suites
	 * @see #getSupportedCipherSuites()
	 * @see DtlsConfig#DTLS_PRESELECTED_CIPHER_SUITES
	 * @since 2.5
	 */
	public List<CipherSuite> getPreselectedCipherSuites() {
		return configuration.get(DtlsConfig.DTLS_PRESELECTED_CIPHER_SUITES);
	}

	/**
	 * Gets the supported cipher suites.
	 * 
	 * On the client side the connector advertise these cipher suites in a DTLS
	 * handshake. On the server side the connector limits the acceptable cipher
	 * suites to this list.
	 * 
	 * If not provided via {@link Builder#setSupportedCipherSuites},
	 * {@link DtlsConfig#DTLS_CIPHER_SUITES} is used instead. if that is also
	 * missing, the supported cipher suites are are setup according the type of
	 * the provided credentials and {@link #getPreselectedCipherSuites()}.
	 * 
	 * @return the supported cipher suites (ordered by preference)
	 * @see Builder#setSupportedCipherSuites
	 * @see DtlsConfig#DTLS_CIPHER_SUITES
	 */
	public List<CipherSuite> getSupportedCipherSuites() {
		return supportedCipherSuites;
	}

	/**
	 * Gets the supported signature and hash algorithms the connector should
	 * advertise in a DTLS handshake.
	 * 
	 * @return the supported signature and hash algorithms (ordered by
	 *         preference). If empty, the client does not advertise it's
	 *         supported signature and hash algorithms, and the server assumes
	 *         the {@link SignatureAndHashAlgorithm#DEFAULT} as list of
	 *         supported signature and hash algorithms
	 * @see Builder#setSupportedSignatureAlgorithms
	 * @see DtlsConfig#DTLS_SIGNATURE_AND_HASH_ALGORITHMS
	 * @since 2.3
	 */
	public List<SignatureAndHashAlgorithm> getSupportedSignatureAlgorithms() {
		return supportedSignatureAlgorithms;
	}

	/**
	 * Gets the supported groups (curves).
	 * 
	 * On the client side the connector advertise these supported groups
	 * (curves) in a DTLS handshake. On the server side the connector limits the
	 * acceptable supported groups (curves) to this list. According
	 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target=
	 * "_blank">RFC 8422, 5.1. Client Hello Extensions, Actions of the
	 * receiver</a> This affects both, curves for ECDH and the certificates for
	 * ECDSA.
	 * 
	 * @return the supported groups (curves, ordered by preference)
	 * @see Builder#setSupportedGroups
	 * @see DtlsConfig#DTLS_CURVES
	 * @since 2.3
	 */
	public List<SupportedGroup> getSupportedGroups() {
		return supportedGroups;
	}

	/**
	 * Gets the advanced registry of <em>shared secrets</em> used for
	 * authenticating clients during a DTLS handshake.
	 * 
	 * @return the registry
	 * @see Builder#setAdvancedPskStore(AdvancedPskStore)
	 * @see AdvancedSinglePskStore
	 * @see AdvancedMultiPskStore
	 * @since 2.3
	 */
	public AdvancedPskStore getAdvancedPskStore() {
		return advancedPskStore;
	}

	/**
	 * Gets the new advanced certificate verifier to be used during the DTLS
	 * handshake.
	 * 
	 * @return the new advanced certificate verifier
	 * @see Builder#setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)
	 * @see StaticNewAdvancedCertificateVerifier
	 * @since 2.5
	 */
	public NewAdvancedCertificateVerifier getAdvancedCertificateVerifier() {
		return advancedCertificateVerifier;
	}

	/**
	 * Gets the supplier of application level information for an authenticated
	 * peer's identity.
	 * 
	 * @return the supplier, or {@code null}, if not set
	 * @see Builder#setApplicationLevelInfoSupplier(ApplicationLevelInfoSupplier)
	 */
	public ApplicationLevelInfoSupplier getApplicationLevelInfoSupplier() {
		return applicationLevelInfoSupplier;
	}

	/**
	 * Gets whether the connector wants (requests) DTLS x509/RPK clients to
	 * authenticate during the handshake. The handshake doesn't fail, if the
	 * client didn't authenticate itself during the handshake. That mostly
	 * requires the client to use a proprietary mechanism to authenticate itself
	 * on the application layer (e.g. username/password). It's mainly used, if
	 * the implementation of the other peer has no PSK cipher suite and client
	 * certificate should not be used for some reason.
	 * 
	 * Only used by the DTLS server side.
	 * 
	 * @return client authentication mode
	 * @see DtlsConfig#DTLS_CLIENT_AUTHENTICATION_MODE
	 * @since 3.0
	 */
	public CertificateAuthenticationMode getCertificateAuthenticationMode() {
		return configuration.get(DtlsConfig.DTLS_CLIENT_AUTHENTICATION_MODE);
	}

	/**
	 * Verify the server certificate's subject.
	 * 
	 * @return {@code true}, to the server certificate's subject, {@code false},
	 *         to not verify it
	 * @see DtlsConfig#DTLS_VERIFY_SERVER_CERTIFICATES_SUBJECT
	 * @since 3.0
	 */
	public Boolean verifyServerCertificatesSubject() {
		return configuration.get(DtlsConfig.DTLS_VERIFY_SERVER_CERTIFICATES_SUBJECT);
	}

	/**
	 * Gets the DTLS role the connector acts as.
	 * 
	 * @return role the connector acts as
	 * @see DtlsConfig#DTLS_ROLE
	 * @since 3.0
	 */
	public DtlsRole getDtlsRole() {
		return configuration.get(DtlsConfig.DTLS_ROLE);
	}

	/**
	 * Get the default handshake mode.
	 * 
	 * Used, if no handshake mode is provided in the endpoint context, see
	 * {@link DtlsEndpointContext#KEY_HANDSHAKE_MODE}.
	 * 
	 * @return default handshake mode.
	 *         {@link DtlsEndpointContext#HANDSHAKE_MODE_NONE} or
	 *         {@link DtlsEndpointContext#HANDSHAKE_MODE_AUTO}. If
	 *         {@link DtlsConfig#DTLS_ROLE} is {@link DtlsRole#SERVER_ONLY}, the
	 *         specified default handshake mode is ignored and
	 *         {@link DtlsEndpointContext#HANDSHAKE_MODE_NONE} is returned
	 *         instead.
	 * @see DtlsConfig#DTLS_DEFAULT_HANDSHAKE_MODE
	 * @see DtlsConfig#DTLS_ROLE
	 * @since 2.1
	 */
	public String getDefaultHandshakeMode() {
		if (getDtlsRole() == DtlsRole.SERVER_ONLY) {
			return DtlsEndpointContext.HANDSHAKE_MODE_NONE;
		} else {
			return configuration.get(DtlsConfig.DTLS_DEFAULT_HANDSHAKE_MODE);
		}
	}

	/**
	 * Gets the certificate types for the identity of this peer.
	 * 
	 * In the order of preference.
	 * 
	 * @return certificate types ordered by preference, or {@code null}, if no
	 *         certificates are used to identify this peer.
	 * @see Builder#setCertificateIdentityProvider(CertificateProvider)
	 * @see CertificateProvider#getSupportedCertificateTypes()
	 */
	public List<CertificateType> getIdentityCertificateTypes() {
		if (certificateIdentityProvider == null) {
			return null;
		}
		return certificateIdentityProvider.getSupportedCertificateTypes();
	}

	/**
	 * Gets the certificate types for the trust of the other peer.
	 * 
	 * In the order of preference.
	 * 
	 * @return certificate types ordered by preference, or {@code null}, if no
	 *         certificates are used to trust the other peer.
	 * @see Builder#setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)
	 * @see NewAdvancedCertificateVerifier#getSupportedCertificateTypes()
	 */
	public List<CertificateType> getTrustCertificateTypes() {
		if (advancedCertificateVerifier == null) {
			return null;
		}
		return advancedCertificateVerifier.getSupportedCertificateTypes();
	}

	/**
	 * Gets the maximum number of (active) connections the connector will
	 * support.
	 * <p>
	 * Once this limit is reached, new connections will only be accepted if
	 * <em>stale</em> connections exist. A stale connection is one that hasn't
	 * been used for at least <em>staleConnectionThreshold</em> seconds.
	 * 
	 * @return The maximum number of active connections supported.
	 * @see DtlsConfig#DTLS_MAX_CONNECTIONS
	 * @see #getStaleConnectionThresholdSeconds()
	 */
	public Integer getMaxConnections() {
		return configuration.get(DtlsConfig.DTLS_MAX_CONNECTIONS);
	}

	/**
	 * Gets the maximum number of seconds within which some records need to be
	 * exchanged over a connection before it is considered <em>stale</em>.
	 * <p>
	 * Once a connection becomes stale, it may be removed for new connections.
	 * 
	 * @return The number of seconds.
	 * @see DtlsConfig#DTLS_STALE_CONNECTION_THRESHOLD
	 * @see #getMaxConnections()
	 * @since 3.0 (renamed, was getStaleConnectionThreshold)
	 */
	public Long getStaleConnectionThresholdSeconds() {
		return configuration.get(DtlsConfig.DTLS_STALE_CONNECTION_THRESHOLD, TimeUnit.SECONDS);
	}

	/**
	 * Gets the number of threads which should be use to handle DTLS connection.
	 * 
	 * @return the number of threads.
	 * @see DtlsConfig#DTLS_CONNECTOR_THREAD_COUNT
	 * @since 3.0 (renamed, was getConnectionThreadCount)
	 */
	public Integer getConnectorThreadCount() {
		return configuration.get(DtlsConfig.DTLS_CONNECTOR_THREAD_COUNT);
	}

	/**
	 * Gets the number of threads which should be use to receive datagrams from
	 * the socket.
	 * 
	 * @return the number of threads.
	 * @see DtlsConfig#DTLS_RECEIVER_THREAD_COUNT
	 */
	public Integer getReceiverThreadCount() {
		return configuration.get(DtlsConfig.DTLS_RECEIVER_THREAD_COUNT);
	}

	/**
	 * Gets size of the socket receive buffer.
	 * 
	 * @return the socket receive buffer in bytes, or {@code null}, to use the
	 *         OS default.
	 * @see DtlsConfig#DTLS_RECEIVE_BUFFER_SIZE
	 */
	public Integer getSocketReceiveBufferSize() {
		return configuration.get(DtlsConfig.DTLS_RECEIVE_BUFFER_SIZE);
	}

	/**
	 * Gets size of the socket send buffer.
	 * 
	 * @return the socket send buffer in bytes, or {@code null}, to use the OS
	 *         default.
	 * @see DtlsConfig#DTLS_SEND_BUFFER_SIZE
	 */
	public Integer getSocketSendBufferSize() {
		return configuration.get(DtlsConfig.DTLS_SEND_BUFFER_SIZE);
	}

	/**
	 * Get the timeout for automatic handshakes.
	 * 
	 * If no messages are exchanged for this timeout, the next message will
	 * trigger a handshake automatically. Intended to be used, if traffic is
	 * routed over a NAT. The value may be overridden by the endpoint context
	 * attribute {@link DtlsEndpointContext#KEY_AUTO_HANDSHAKE_TIMEOUT}.
	 * 
	 * @return timeout in milliseconds, or {@code null}, if no automatic
	 *         resumption is intended. Values less the 1 milliseconds will be
	 *         returned as {@code null}.
	 * @see DtlsConfig#DTLS_AUTO_HANDSHAKE_TIMEOUT
	 * @since 3.0 (renamed, was getAuteResumptionTimeoutMillis)
	 */
	public Long getAutoHandshakeTimeoutMillis() {
		Long timeout = configuration.get(DtlsConfig.DTLS_AUTO_HANDSHAKE_TIMEOUT, TimeUnit.MILLISECONDS);
		if (timeout != null && timeout <= 0) {
			timeout = null;
		}
		return timeout;
	}

	/**
	 * Indicates, that session id is used by this server and so session are
	 * cached by this server and can be resumed.
	 * 
	 * @return {@code true}, if session id is used by this server,
	 *         {@code false}, if no session id us used by this server and
	 *         therefore the session can not be resumed. Default {@code true}.
	 * @see DtlsConfig#DTLS_SERVER_USE_SESSION_ID
	 * @since 3.0 (was useNoServerSessionId with inverse logic)
	 */
	public Boolean useServerSessionId() {
		return configuration.get(DtlsConfig.DTLS_SERVER_USE_SESSION_ID);
	}

	/**
	 * Use anti replay filter.
	 * 
	 * @return {@code true}, apply anti replay filter
	 * @see <a href= "https://tools.ietf.org/html/rfc6347#section-4.1.2.6"
	 *      target= "_blank">RFC6347 4.1.2.6. Anti-Replay</a>
	 * @see DtlsConfig#DTLS_USE_ANTI_REPLAY_FILTER
	 */
	public Boolean useAntiReplayFilter() {
		return configuration.get(DtlsConfig.DTLS_USE_ANTI_REPLAY_FILTER);
	}

	/**
	 * Use disabled window for anti replay filter.
	 * 
	 * Californium uses the "sliding receive window" approach mentioned in
	 * <a href= "https://tools.ietf.org/html/rfc6347#section-4.1.2.6" target=
	 * "_blank">RFC6347 4.1.2.6. Anti-Replay</a>. That causes trouble, if some
	 * records are sent on postponed routes (e.g. SMS). That would make it more
	 * probable, that the record is to old for the receive window. In order not
	 * to discard such records, this values defines a "disabled window", that
	 * allows record to pass the filter, even if the records are too old for the
	 * current receive window.
	 * 
	 * The value will be subtracted from to lower receive window boundary. A
	 * value of {@code -1} will set that calculated lower boundary to {@code 0}.
	 * Messages between lower receive window boundary and that calculated value
	 * will pass the filter, for other messages the filter is applied.
	 * 
	 * @return value to extend lower receive window boundary, {@code -1}, to
	 *         extend lower boundary to {@code 0}, {@code 0} to disable extended
	 *         window filter.
	 * @see <a href= "https://tools.ietf.org/html/rfc6347#section-4.1.2.6"
	 *      target= "_blank">RFC6347 4.1.2.6. Anti-Replay</a>
	 * @see DtlsConfig#DTLS_USE_DISABLED_WINDOW_FOR_ANTI_REPLAY_FILTER
	 * @since 2.4
	 */
	public Integer useDisabledWindowFilter() {
		return configuration.get(DtlsConfig.DTLS_USE_DISABLED_WINDOW_FOR_ANTI_REPLAY_FILTER);
	}

	/**
	 * Update the ip-address from DTLS 1.2 CID records only for newer records
	 * based on epoch/sequence_number.
	 * 
	 * The MAC filter must always be passed.
	 * 
	 * @return {@code true}, update ip-address only for newer records,
	 *         {@code false}, update ip-address for records passing the
	 *         anti-replay-filter.
	 * @see DtlsConfig#DTLS_UPDATE_ADDRESS_USING_CID_ON_NEWER_RECORDS
	 * @since 3.0 (renamed, was useCidUpdateAddressOnNewerRecordFilter)
	 */
	public Boolean useUpdateAddressUsingCidOnNewerRecords() {
		return configuration.get(DtlsConfig.DTLS_UPDATE_ADDRESS_USING_CID_ON_NEWER_RECORDS);
	}

	/**
	 * Use truncated certificate paths for client's certificate message.
	 * 
	 * Truncate certificate path according the received certificate authorities
	 * in the {@link CertificateRequest} for the client's
	 * {@link CertificateMessage}.
	 * 
	 * @return {@code true}, if path should be truncated for client's
	 *         certificate message.
	 * @see DtlsConfig#DTLS_TRUNCATE_CLIENT_CERTIFICATE_PATH
	 * @since 2.1
	 */
	public Boolean useTruncatedCertificatePathForClientsCertificateMessage() {
		return configuration.get(DtlsConfig.DTLS_TRUNCATE_CLIENT_CERTIFICATE_PATH);
	}

	/**
	 * Use truncated certificate paths for validation.
	 * 
	 * Truncate certificate path according the available trusted certificates
	 * before validation.
	 * 
	 * @return {@code true}, if path should be truncated at available trust
	 *         anchors for validation
	 * @see DtlsConfig#DTLS_TRUNCATE_CERTIFICATE_PATH_FOR_VALIDATION
	 * @since 2.1
	 */
	public Boolean useTruncatedCertificatePathForValidation() {
		return configuration.get(DtlsConfig.DTLS_TRUNCATE_CERTIFICATE_PATH_FOR_VALIDATION);
	}

	/**
	 * Gets connection listener.
	 * 
	 * @return connection listener
	 * @see Builder#setConnectionListener(ConnectionListener)
	 */
	public ConnectionListener getConnectionListener() {
		return connectionListener;
	}

	/**
	 * Gets session store for {@link InMemoryConnectionStore}.
	 * 
	 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
	 * session store must be provided directly to that implementation. In that
	 * case, the configured session store here will be ignored.
	 * 
	 * @return session store, or {@code null}, if not provided.
	 * @see Builder#setSessionStore(SessionStore)
	 * @see DTLSConnector#createConnectionStore
	 * @since 3.0
	 */
	public SessionStore getSessionStore() {
		return sessionStore;
	}

	/**
	 * Gets the resumption verifier.
	 * 
	 * If the client provides a session id in the client hello, this verifier is
	 * used to ensure, that a valid session to resume is available. An
	 * implementation may check a maximum time, or, if the credentials are
	 * expired (e.g. x509 valid range). The default verifier will just checks,
	 * if a DTLS session with that session id is available in the
	 * {@link ResumptionSupportingConnectionStore}.
	 * 
	 * @return resumption verifier. May be {@code null}, if
	 *         {@link #useServerSessionId()} is {@code false} and session
	 *         resumption is not supported.
	 * @see Builder#setResumptionVerifier(ResumptionVerifier)
	 * @since 3.0
	 */
	public ResumptionVerifier getResumptionVerifier() {
		return resumptionVerifier;
	}

	/**
	 * Get instance logging tag.
	 * 
	 * @return logging tag.
	 * @see Builder#setLoggingTag(String)
	 */
	public String getLoggingTag() {
		return loggingTag;
	}

	/**
	 * Gets health status interval.
	 * 
	 * @return health status interval in milliseconds. {@code 0} for disabled.
	 * @see SystemConfig#HEALTH_STATUS_INTERVAL
	 * @since 3.0 (renamed, was getHealthStatusInterval, changed return type to
	 *        {@code int} and returns milliseconds instead of seconds)
	 */
	public int getHealthStatusIntervalMilliseconds() {
		return configuration.getTimeAsInt(SystemConfig.HEALTH_STATUS_INTERVAL, TimeUnit.MILLISECONDS);
	}

	/**
	 * Gets health handler.
	 * 
	 * @return health handler.
	 * @see Builder#setHealthHandler(DtlsHealth)
	 */
	public DtlsHealth getHealthHandler() {
		return healthHandler;
	}

	/**
	 * Check, if only recommended cipher suite are to be used.
	 * 
	 * @return {@code true}, if only recommended cipher suites are used.
	 * @see DtlsConfig#DTLS_RECOMMENDED_CIPHER_SUITES_ONLY
	 * @since 3.0 (renamed, was isRecommendedCipherSuitesOnly)
	 */
	public Boolean useRecommendedCipherSuitesOnly() {
		return configuration.get(DtlsConfig.DTLS_RECOMMENDED_CIPHER_SUITES_ONLY);
	}

	/**
	 * Check, if only recommended supported curves are to be used.
	 * 
	 * @return {@code true}, if only recommended supported groups (curves) are
	 *         used.
	 * @see DtlsConfig#DTLS_RECOMMENDED_CURVES_ONLY
	 * @since 3.0 (renamed, was isRecommendedSupportedGroupsOnly)
	 */
	public Boolean useRecommendedSupportedGroupsOnly() {
		return configuration.get(DtlsConfig.DTLS_RECOMMENDED_CURVES_ONLY);
	}

	/**
	 * Check, if only recommended signature and hash algorithms are used.
	 * 
	 * @return {@code true}, if only recommended signature and hash algorithms
	 *         are used.
	 * @see DtlsConfig#DTLS_RECOMMENDED_SIGNATURE_AND_HASH_ALGORITHMS_ONLY
	 * @since 3.0
	 */
	public Boolean useRecommendedSignatureAndHashAlgorithmsOnly() {
		return configuration.get(DtlsConfig.DTLS_RECOMMENDED_SIGNATURE_AND_HASH_ALGORITHMS_ONLY);
	}

	/**
	 * @return a copy of this configuration
	 */
	@Override
	protected Object clone() {
		DtlsConnectorConfig cloned = new DtlsConnectorConfig(configuration);
		cloned.address = address;
		cloned.advancedCertificateVerifier = advancedCertificateVerifier;
		cloned.useReuseAddress = useReuseAddress;
		cloned.protocolVersionForHelloVerifyRequests = protocolVersionForHelloVerifyRequests;
		cloned.advancedPskStore = advancedPskStore;
		cloned.certificateIdentityProvider = certificateIdentityProvider;
		cloned.certificateConfigurationHelper = certificateConfigurationHelper;
		cloned.cipherSuiteSelector = cipherSuiteSelector;
		cloned.supportedCipherSuites = supportedCipherSuites;
		cloned.supportedSignatureAlgorithms = supportedSignatureAlgorithms;
		cloned.supportedGroups = supportedGroups;
		cloned.loggingTag = loggingTag;
		cloned.connectionIdGenerator = connectionIdGenerator;
		cloned.applicationLevelInfoSupplier = applicationLevelInfoSupplier;
		cloned.connectionListener = connectionListener;
		cloned.sessionStore = sessionStore;
		cloned.resumptionVerifier = resumptionVerifier;
		cloned.healthHandler = healthHandler;
		return cloned;
	}

	/**
	 * Create new builder for DtlsConnectorConfig.
	 * 
	 * @param configuration the configuration with {@link DtlsConfig}
	 *            definitions. Cloned, changes on the provided configuration
	 *            don't affect this builder.
	 * @return created builder
	 * @throws NullPointerException if configuration is {@code null}
	 * @since 3.0
	 */
	public static Builder builder(Configuration configuration) {
		return new Builder(configuration);
	}

	/**
	 * Create builder for DtlsConnectorConfig from provided DtlsConnectorConfig.
	 * 
	 * @param config DtlsConnectorConfig to clone
	 * @return created builder
	 * @since 2.5
	 */
	public static Builder builder(DtlsConnectorConfig config) {
		return new Builder(config);
	}

	/**
	 * A helper for creating instances of {@code DtlsConnectorConfig} based
	 * on the builder pattern.
	 */
	public static final class Builder {

		private DtlsConnectorConfig config;

		/**
		 * Creates a new instance for setting configuration options for a
		 * {@code DTLSConnector} instance.
		 * 
		 * Once all options are set, clients should use the {@link #build()}
		 * method to create an immutable {@code DtlsConfigurationConfig}
		 * instance which can be passed into the {@code DTLSConnector}
		 * constructor.
		 * 
		 * Note that when keeping the default values, at least one of the
		 * {@link #setAdvancedPskStore(AdvancedPskStore)} or
		 * {@link #setCertificateIdentityProvider(CertificateProvider)} methods
		 * need to be used to get a working configuration for a
		 * {@code DTLSConnector} that can be used as a client and server.
		 * 
		 * It is possible to create a configuration for a {@code DTLSConnector}
		 * that can operate as a client only without the need for setting an
		 * identity. However, this is possible only if the server does not
		 * require clients to authenticate, i.e. this only works with the ECDSA
		 * based cipher suites. If you want to create such a
		 * <em>client-only</em> configuration, you need to use the
		 * {@link DtlsConfig#DTLS_ROLE} with {@link DtlsRole#CLIENT_ONLY}.
		 * 
		 * @param configuration the configuration with {@link DtlsConfig}
		 *            definitions. Cloned, changes on the provided configuration
		 *            don't affect this builder.
		 * @throws NullPointerException if configuration is {@code null}
		 * @since 3.0
		 */
		public Builder(Configuration configuration) {
			config = new DtlsConnectorConfig(configuration);
		}

		/**
		 * Create a builder from an existing DtlsConnectorConfig. This allow to
		 * create a new configuration starting from values of another one.
		 * 
		 * @param initialConfiguration initial configuration
		 */
		private Builder(DtlsConnectorConfig initialConfiguration) {
			config = (DtlsConnectorConfig) initialConfiguration.clone();
		}

		/**
		 * Associates the specified value with the specified definition.
		 * 
		 * @param <T> value type
		 * @param definition the value definition
		 * @param value the value
		 * @return this builder for command chaining
		 * @throws NullPointerException if the definition is {@code null}
		 * @throws IllegalArgumentException if a different definition is already
		 *             available for the key of the provided definition.
		 * @since 3.0
		 */
		public <T> Builder set(BasicDefinition<T> definition, T value) {
			config.configuration.set(definition, value);
			return this;
		}

		/**
		 * Associates the specified list of values with the specified
		 * definition.
		 * 
		 * @param <T> item value type
		 * @param definition the value definition
		 * @param values the list of values
		 * @return the configuration for chaining
		 * @throws NullPointerException if the definition or values is
		 *             {@code null}
		 * @throws IllegalArgumentException if a different definition is already
		 *             available for the key of the provided definition or the
		 *             values are empty.
		 * @since 3.0
		 */
		public <T extends Enum<?>> Builder setList(EnumListDefinition<T> definition,
				@SuppressWarnings("unchecked") T... values) {
			config.configuration.setList(definition, values);
			return this;
		}

		/**
		 * Associates the specified time value with the specified definition.
		 * 
		 * @param definition the value definition
		 * @param value the value
		 * @param unit the time unit of the value
		 * @return this builder for command chaining
		 * @throws NullPointerException if the definition or unit is
		 *             {@code null}
		 * @throws IllegalArgumentException if a different definition is already
		 *             available for the key of the provided definition.
		 * @since 3.0
		 */
		public Builder set(TimeDefinition definition, Long value, TimeUnit unit) {
			config.configuration.set(definition, value, unit);
			return this;
		}

		/**
		 * Associates the specified time value with the specified definition.
		 * 
		 * @param definition the value definition
		 * @param value the value
		 * @param unit the time unit of the value
		 * @return this builder for command chaining
		 * @throws NullPointerException if the definition or unit is
		 *             {@code null}
		 * @throws IllegalArgumentException if a different definition is already
		 *             available for the key of the provided definition.
		 * @since 3.0
		 */
		public Builder set(TimeDefinition definition, int value, TimeUnit unit) {
			config.configuration.set(definition, value, unit);
			return this;
		}

		/**
		 * Sets the IP address and port the connector should bind to
		 * 
		 * Note: using IPv6 interfaces with multiple addresses including
		 * permanent and temporary (with potentially several different prefixes)
		 * currently causes issues on the server side. The outgoing traffic in
		 * response to incoming may select a different source address than the
		 * incoming destination address. To overcome this, please ensure that
		 * the 'any address' is not used on the server side and a separate
		 * Connector is created for each address to receive incoming traffic.
		 * 
		 * @param address the IP address and port the connector should bind to
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the given address is unresolved
		 * @see DtlsConnectorConfig#getAddress()
		 */
		public Builder setAddress(InetSocketAddress address) {
			if (address.isUnresolved()) {
				throw new IllegalArgumentException("Bind address must not be unresolved");
			}
			config.address = address;
			return this;
		}

		/**
		 * Enables address reuse for the socket.
		 * 
		 * @param enable {@code true} if addresses should be reused.
		 * @return this builder for command chaining
		 * @see DtlsConnectorConfig#useReuseAddress()
		 */
		public Builder setReuseAddress(boolean enable) {
			config.useReuseAddress = enable;
			return this;
		}

		/**
		 * Set the protocol version to be used to send hello verify requests.
		 * 
		 * Before version 2.5.0, Californium used fixed the protocol version
		 * DTLS 1.2 to send the HelloVerifyRequest. According
		 * <a href="https://tools.ietf.org/html/rfc6347#section-4.2.1" target=
		 * "_blank">RFC 6347, 4.2.1. Denial-of-Service Countermeasures</a>, that
		 * HelloVerifyRequest SHOULD be sent using protocol version DTLS 1.0.
		 * But that found to be ambiguous, because it's also requested that "The
		 * server MUST use the same version number in the HelloVerifyRequest
		 * that it would use when sending a ServerHello." With that, Californium
		 * from 2.6.0 on will, by default, reply the version the client sent in
		 * the HelloVerifyRequest, and will postpone the version negotiation
		 * until the client has verified it's endpoint ownership. If that client
		 * version is below DTLS 1.0, a DTLS 1.0 will be used. If a different
		 * behavior is wanted, you may use this setter to provide a fixed
		 * version for the HelloVerifyRequest. In order to provide backwards
		 * compatibility to version before 2.5.0, configure to use protocol
		 * version DTLS 1.2.
		 * 
		 * <b>Note:</b> this property is considered to be changed only for very
		 * exotic use-cases. Therefore it's not included in the
		 * {@link DtlsConfig}.
		 * 
		 * @param protocolVersion fixed protocol version to send hello verify
		 *            requests. {@code null} to reply the client's version.
		 * @return this builder for command chaining
		 * @see HelloVerifyRequest
		 * @see DtlsConnectorConfig#getProtocolVersionForHelloVerifyRequests()
		 * @since 2.5
		 */
		public Builder setProtocolVersionForHelloVerifyRequests(ProtocolVersion protocolVersion) {
			config.protocolVersionForHelloVerifyRequests = protocolVersion;
			return this;
		}

		/**
		 * Set the health handler.
		 * 
		 * @param healthHandler health handler.
		 * @return this builder for command chaining
		 * @see DtlsConnectorConfig#getHealthHandler()
		 */
		public Builder setHealthHandler(DtlsHealth healthHandler) {
			config.healthHandler = healthHandler;
			return this;
		}

		/**
		 * Sets the cipher suite selector.
		 * <p>
		 * The connector will use these selector to determine the cipher suite
		 * and parameters during the handshake.
		 * 
		 * @param cipherSuiteSelector the cipher suite selector. Default
		 *            ({@link DefaultCipherSuiteSelector}.
		 * @return this builder for command chaining
		 * @see DtlsConnectorConfig#getCipherSuiteSelector()
		 * @since 2.3
		 */
		public Builder setCipherSuiteSelector(CipherSuiteSelector cipherSuiteSelector) {
			config.cipherSuiteSelector = cipherSuiteSelector;
			return this;
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the supported cipher suites in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains {@link CipherSuite#TLS_NULL_WITH_NULL_NULL},
		 *             contains a cipher suite, not supported by the JVM,
		 *             violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_CIPHER_SUITES_ONLY}
		 *             setting, or the use of HELLO_VERIFY_REQUEST is disabled
		 *             and no PSK cipher suite is contained.
		 * @see DtlsConnectorConfig#getSupportedCipherSuites()
		 */
		public Builder setSupportedCipherSuites(CipherSuite... cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			return setSupportedCipherSuites(Arrays.asList(cipherSuites));
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the supported cipher suites in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given list is {@code null}
		 * @throws IllegalArgumentException if the given list is empty, contains
		 *             {@link CipherSuite#TLS_NULL_WITH_NULL_NULL}, contains a
		 *             cipher suite, not supported by the JVM, violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_CIPHER_SUITES_ONLY}
		 *             setting, or the use of HELLO_VERIFY_REQUEST is disabled
		 *             and no PSK cipher suite is contained.
		 * @see DtlsConnectorConfig#getSupportedCipherSuites()
		 */
		public Builder setSupportedCipherSuites(List<CipherSuite> cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			if (cipherSuites.isEmpty()) {
				throw new IllegalArgumentException("Connector must support at least one cipher suite");
			}
			if (cipherSuites.contains(CipherSuite.TLS_NULL_WITH_NULL_NULL)) {
				throw new IllegalArgumentException("NULL Cipher Suite is not supported by connector");
			}
			if (!config.useHelloVerifyRequestForPsk()) {
				if (!CipherSuite.containsPskBasedCipherSuite(cipherSuites)) {
					throw new IllegalArgumentException(
							"HELLO_VERIFY_REQUEST disabled, requires at least on PSK cipher suite!");
				}
			}
			if (config.useRecommendedCipherSuitesOnly()) {
				verifyRecommendedCipherSuitesOnly(cipherSuites);
			}
			for (CipherSuite cipherSuite : cipherSuites) {
				if (!cipherSuite.isSupported()) {
					throw new IllegalArgumentException("cipher-suites " + cipherSuite + " is not supported by JVM!");
				}
			}

			config.supportedCipherSuites = cipherSuites;
			return this;
		}

		/**
		 * Sets the cipher suites supported by the connector.
		 * <p>
		 * The connector will use these cipher suites (in exactly the same
		 * order) during the DTLS handshake when negotiating a cipher suite with
		 * a peer.
		 * 
		 * @param cipherSuites the names of supported cipher suites in the order
		 *            of preference (see <a href=
		 *            "https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-4"
		 *            target="_blank"> IANA registry</a> for a list of cipher
		 *            suite names)
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains {@link CipherSuite#TLS_NULL_WITH_NULL_NULL},
		 *             contains a cipher suite, not supported by the JVM,
		 *             contains a name, which is not supported, violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_CIPHER_SUITES_ONLY}
		 *             setting, or the use of HELLO_VERIFY_REQUEST is disabled
		 *             and no PSK cipher suite is contained.
		 * @see DtlsConnectorConfig#getSupportedCipherSuites()
		 */
		public Builder setSupportedCipherSuites(String... cipherSuites) {
			if (cipherSuites == null) {
				throw new NullPointerException("Connector must support at least one cipher suite");
			}
			List<CipherSuite> suites = CipherSuite.getTypesByNames(cipherSuites);
			return setSupportedCipherSuites(suites);
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the supported signature
		 *            algorithms in the order of preference. No arguments, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_SIGNATURE_AND_HASH_ALGORITHMS_ONLY}
		 *             setting.
		 * @see DtlsConnectorConfig#getSupportedSignatureAlgorithms()
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly
		 *        violations)
		 */
		public Builder setSupportedSignatureAlgorithms(SignatureAndHashAlgorithm... supportedSignatureAlgorithms) {
			if (supportedSignatureAlgorithms == null) {
				config.supportedSignatureAlgorithms = null;
				return this;
			} else {
				return setSupportedSignatureAlgorithms(Arrays.asList(supportedSignatureAlgorithms));
			}
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the list of supported signature
		 *            algorithms in the order of preference. Empty, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_SIGNATURE_AND_HASH_ALGORITHMS_ONLY}
		 *             setting.
		 * @see DtlsConnectorConfig#getSupportedSignatureAlgorithms()
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly
		 *        violations)
		 */
		public Builder setSupportedSignatureAlgorithms(List<SignatureAndHashAlgorithm> supportedSignatureAlgorithms) {
			if (supportedSignatureAlgorithms != null && config.useRecommendedSignatureAndHashAlgorithmsOnly()) {
				verifyRecommendedSignatureAndHashAlgorithmsOnly(supportedSignatureAlgorithms);
			}
			config.supportedSignatureAlgorithms = supportedSignatureAlgorithms;
			return this;
		}

		/**
		 * Sets the signature algorithms supported by the connector.
		 * <p>
		 * The connector will use these signature algorithms (in exactly the
		 * same order) during the DTLS handshake.
		 * 
		 * @param supportedSignatureAlgorithms the list of supported signature
		 *            algorithm names in the order of preference. Empty, if no
		 *            specific extension is to be used for a client, and the
		 *            server uses {@link SignatureAndHashAlgorithm#DEFAULT}.
		 * @return this builder for command chaining
		 * @throws IllegalArgumentException if the list violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_SIGNATURE_AND_HASH_ALGORITHMS_ONLY}
		 *             setting or not supported signature and algorithms are
		 *             contained in the list.
		 * @see SignatureAndHashAlgorithm#valueOf(String)
		 * @see DtlsConnectorConfig#getSupportedSignatureAlgorithms()
		 * @since 3.0 (reports recommendedSignatureAndHashAlgorithmsOnly
		 *        violations)
		 */
		public Builder setSupportedSignatureAlgorithms(String... supportedSignatureAlgorithms) {
			List<SignatureAndHashAlgorithm> list = null;
			if (supportedSignatureAlgorithms != null) {
				list = new ArrayList<SignatureAndHashAlgorithm>(supportedSignatureAlgorithms.length);
				for (int i = 0; i < supportedSignatureAlgorithms.length; i++) {
					SignatureAndHashAlgorithm signatureAndHashAlgorithm = SignatureAndHashAlgorithm
							.valueOf(supportedSignatureAlgorithms[i]);
					if (signatureAndHashAlgorithm != null) {
						list.add(signatureAndHashAlgorithm);
					} else {
						throw new IllegalArgumentException(
								String.format("Signature and hash algorithm [%s] is not (yet) supported",
										supportedSignatureAlgorithms[i]));
					}
				}
			}
			return setSupportedSignatureAlgorithms(list);
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target=
		 * "_blank">RFC 8422, 5.1. Client Hello Extensions, Actions of the
		 * receiver</a> This affects both, curves for ECDH and the certificates
		 * for ECDSA.
		 * 
		 * @param supportedGroups the supported groups (curves) in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains a group (curve), not supported by the JVM, or
		 *             violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_CURVES_ONLY} setting.
		 * @see DtlsConnectorConfig#getSupportedGroups()
		 * @since 2.3
		 */
		public Builder setSupportedGroups(SupportedGroup... supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one group (curve)");
			}
			return setSupportedGroups(Arrays.asList(supportedGroups));
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target=
		 * "_blank">RFC 8422, 5.1. Client Hello Extensions, Actions of the
		 * receiver</a> This affects both, curves for ECDH and the certificates
		 * for ECDSA.
		 * 
		 * @param supportedGroups the supported groups (curves) in the order of
		 *            preference
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given list is {@code null}
		 * @throws IllegalArgumentException if the given list is empty, contains
		 *             a group (curve), not supported by the JVM, or violates
		 *             the {@link DtlsConfig#DTLS_RECOMMENDED_CURVES_ONLY}
		 *             setting.
		 * @see DtlsConnectorConfig#getSupportedGroups()
		 * @since 2.3
		 */
		public Builder setSupportedGroups(List<SupportedGroup> supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one group (curve)");
			}
			if (supportedGroups.isEmpty()) {
				throw new IllegalArgumentException("Connector must support at least one group (curve)");
			}
			if (config.useRecommendedSupportedGroupsOnly()) {
				verifyRecommendedSupportedGroupsOnly(supportedGroups);
			}
			for (SupportedGroup group : supportedGroups) {
				if (!group.isUsable()) {
					throw new IllegalArgumentException("curve " + group.name() + " is not supported by JVM!");
				}
			}

			config.supportedGroups = supportedGroups;
			return this;
		}

		/**
		 * Sets the groups (curves) supported by the connector.
		 * <p>
		 * The connector will use these supported groups (in exactly the same
		 * order) during the DTLS handshake when negotiating a curve with a
		 * peer. According
		 * <a href="https://tools.ietf.org/html/rfc8422#page-11" target=
		 * "_blank"> RFC 8422, 5.1. Client Hello Extensions, Actions of the
		 * receiver</a> this affects both, curves for ECDH and the certificates
		 * for ECDSA.
		 * 
		 * @param supportedGroups the names of supported groups (curves) in the
		 *            order of preference (see <a href=
		 *            "https://www.iana.org/assignments/tls-parameters/tls-parameters.xhtml#tls-parameters-8"
		 *            target="_blank"> IANA registry</a> for a list of supported
		 *            group names)
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given array is {@code null}
		 * @throws IllegalArgumentException if the given array is empty,
		 *             contains a group (curve), not supported by the JVM, or
		 *             violates the
		 *             {@link DtlsConfig#DTLS_RECOMMENDED_CURVES_ONLY} setting.
		 * @see DtlsConnectorConfig#getSupportedGroups()
		 * @since 2.3
		 */
		public Builder setSupportedGroups(String... supportedGroups) {
			if (supportedGroups == null) {
				throw new NullPointerException("Connector must support at least one supported group (curve)");
			}
			List<SupportedGroup> groups = new ArrayList<>(supportedGroups.length);
			for (int i = 0; i < supportedGroups.length; i++) {
				SupportedGroup knownGroup = SupportedGroup.valueOf(supportedGroups[i]);
				if (knownGroup != null) {
					groups.add(knownGroup);
				} else {
					throw new IllegalArgumentException(
							String.format("Group (curve) [%s] is not (yet) supported", supportedGroups[i]));
				}
			}
			return setSupportedGroups(groups);
		}

		/**
		 * Sets the advanced key store to use for authenticating clients based
		 * on a pre-shared key.
		 * 
		 * If used together with
		 * {@link #setCertificateIdentityProvider(CertificateProvider)} the
		 * default preference uses the certificate based cipher suites. To
		 * change that, use {@link #setSupportedCipherSuites(CipherSuite...)} or
		 * {@link #setSupportedCipherSuites(String...)}.
		 * 
		 * @param advancedPskStore the advanced key store
		 * @return this builder for command chaining
		 * @see DtlsConnectorConfig#getAdvancedPskStore()
		 * @since 2.3
		 */
		public Builder setAdvancedPskStore(AdvancedPskStore advancedPskStore) {
			config.advancedPskStore = advancedPskStore;
			return this;
		}

		/**
		 * Sets the connector's certificate identifying provider.
		 * <p>
		 * Please ensure, that you setup
		 * {@link #setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)},
		 * if you want to trust the other peers.
		 * 
		 * If used together with {@link #setAdvancedPskStore(AdvancedPskStore)},
		 * the default preference uses this certificate based cipher suites. To
		 * change that, use {@link #setSupportedCipherSuites(CipherSuite...)} or
		 * {@link #setSupportedCipherSuites(String...)}.
		 * 
		 * For cases, where only a single certificate based identity is used, a
		 * instance of {@link SingleCertificateProvider} may be provided.
		 * 
		 * @param certificateIdentityProvider the certificate identity provider
		 * @return this builder for command chaining
		 * @see #setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier)
		 * @see DtlsConnectorConfig#getCertificateIdentityProvider()
		 * @since 3.0
		 */
		public Builder setCertificateIdentityProvider(CertificateProvider certificateIdentityProvider) {
			config.certificateIdentityProvider = certificateIdentityProvider;
			return this;
		}

		/**
		 * Sets the logic in charge of validating a X.509 certificate chain.
		 *
		 * Here are a few use cases where a custom implementation would be
		 * needed:
		 * <ul>
		 * <li>client certificate authentication based on a dynamic trusted CA
		 * <li>revocation not provided by the default implementation (e.g. OCSP)
		 * <li>cipher suites restriction per client
		 * </ul>
		 * 
		 * @param verifier new advanced certificate verifier
		 * @return this builder for command chaining
		 * @throws NullPointerException if the given certificate verifier is
		 *             {@code null}
		 * @see DtlsConnectorConfig#getAdvancedCertificateVerifier()
		 * @since 2.5
		 */
		public Builder setAdvancedCertificateVerifier(NewAdvancedCertificateVerifier verifier) {
			if (verifier == null) {
				throw new NullPointerException("CertificateVerifier must not be null");
			}
			config.advancedCertificateVerifier = verifier;
			return this;
		}

		/**
		 * Sets a supplier of application level information for an authenticated
		 * peer's identity.
		 * 
		 * @param supplier The supplier.
		 * @return this builder for command chaining.
		 * @throws NullPointerException if supplier is {@code null}.
		 * @see DtlsConnectorConfig#getApplicationLevelInfoSupplier()
		 */
		public Builder setApplicationLevelInfoSupplier(ApplicationLevelInfoSupplier supplier) {
			if (supplier == null) {
				throw new NullPointerException("Supplier must not be null");
			}
			config.applicationLevelInfoSupplier = supplier;
			return this;
		}

		/**
		 * Sets the connection id generator.
		 * 
		 * If no connection ID generator is provided, the value of
		 * {@link DtlsConfig#DTLS_CONNECTION_ID_LENGTH} is used to create a
		 * {@link SingleNodeConnectionIdGenerator}, if set. If additionally
		 * {@link DtlsConfig#DTLS_CONNECTION_ID_NODE_ID} is available, a
		 * {@link MultiNodeConnectionIdGenerator} is created, but requires a CID
		 * length of at least 5 bytes throwing {@link IllegalStateException} on
		 * less.
		 * 
		 * @param connectionIdGenerator connection id generator. {@code null}
		 *            for not supported. The generator may only support the use
		 *            of a connection id without using it by itself. In that
		 *            case {@link ConnectionIdGenerator#useConnectionId()} must
		 *            return {@code false}.
		 * @return this builder for command chaining.
		 * @see DtlsConnectorConfig#getConnectionIdGenerator()
		 */
		public Builder setConnectionIdGenerator(ConnectionIdGenerator connectionIdGenerator) {
			config.connectionIdGenerator = connectionIdGenerator;
			return this;
		}

		/**
		 * Set instance logging tag.
		 * 
		 * @param tag logging tag of configure instance
		 * @return this builder for command chaining.
		 * @see DtlsConnectorConfig#getLoggingTag()
		 */
		public Builder setLoggingTag(String tag) {
			config.loggingTag = tag;
			return this;
		}

		/**
		 * Set connection listener.
		 * 
		 * @param connectionListener connection listener
		 * @return this builder for command chaining.
		 * @see DtlsConnectorConfig#getConnectionListener()
		 */
		public Builder setConnectionListener(ConnectionListener connectionListener) {
			config.connectionListener = connectionListener;
			return this;
		}

		/**
		 * Sets the session store for {@link InMemoryConnectionStore}.
		 * 
		 * If a custom {@link ResumptionSupportingConnectionStore} is used, the
		 * session store must be provided directly to that implementation. In
		 * that case, the configured session store here will be ignored.
		 * 
		 * @param sessionStore session store, or {@code null}, if not to be
		 *            used.
		 * @return this builder for command chaining.
		 * 
		 * @see DTLSConnector#createConnectionStore
		 * @see DtlsConnectorConfig#getSessionStore()
		 * @since 3.0
		 */
		public Builder setSessionStore(SessionStore sessionStore) {
			config.sessionStore = sessionStore;
			return this;
		}

		/**
		 * Sets the resumption verifier.
		 * 
		 * If the client provides a session id in the client hello, this
		 * verifier is used to ensure, that a valid session to resume is
		 * available. An implementation may check a maximum time, or, if the
		 * credentials are expired (e.g. x509 valid range). The default verifier
		 * will just checks, if a DTLS session with that session id is available
		 * in the {@link ResumptionSupportingConnectionStore}.
		 * 
		 * @param resumptionVerifier the resumption verifier
		 * @return this builder for command chaining.
		 * @see DtlsConnectorConfig#getResumptionVerifier()
		 * @since 3.0
		 */
		public Builder setResumptionVerifier(ResumptionVerifier resumptionVerifier) {
			config.resumptionVerifier = resumptionVerifier;
			return this;
		}

		/**
		 * Set certificate configuration helper.
		 * 
		 * @param helper custom certificate configuration helper
		 * @return this builder for command chaining.
		 * @since 3.0
		 */
		public Builder setCertificateHelper(CertificateConfigurationHelper helper) {
			config.certificateConfigurationHelper = helper;
			return this;
		}

		/**
		 * Returns a potentially incomplete configuration. Only fields set by
		 * users are affected, there is no default value, no consistency check.
		 * To get a full usable {@link DtlsConnectorConfig} use {@link #build()}
		 * instead.
		 * 
		 * @return the incomplete Configuration
		 */
		public DtlsConnectorConfig getIncompleteConfig() {
			return config;
		}

		/**
		 * Creates an instance of {@code DtlsConnectorConfig} based on the
		 * properties set on this builder.
		 * <p>
		 * If some parameter are not set, the builder tries to derive a
		 * reasonable values from the other parameters.
		 * 
		 * @return the configuration object
		 * @throws IllegalStateException if the configuration is inconsistent
		 */
		public DtlsConnectorConfig build() {
			// set default values
			config.loggingTag = StringUtil.normalizeLoggingTag(config.loggingTag);
			if (config.address == null) {
				config.address = new InetSocketAddress(0);
			}
			if (config.useReuseAddress == null) {
				config.useReuseAddress = Boolean.FALSE;
			}
			Integer backoff = config.configuration.get(DtlsConfig.DTLS_RETRANSMISSION_BACKOFF);
			if (backoff != null && backoff >= config.getMaxRetransmissions()) {
				throw new IllegalStateException("Backoff for handshake retransmissions (" + backoff
						+ ") must be less than the maximum retransmissions (" + config.getMaxRetransmissions() + ")!");
			}

			if (config.getRetransmissionTimeout() > config.getMaxRetransmissionTimeout()) {
				throw new IllegalStateException("Retransmission timeout " + config.getRetransmissionTimeout()
						+ " is more than the maximum " + config.getMaxRetransmissionTimeout() + "!");
			}

			if (config.getRetransmissionTimeout() <= 0) {
				throw new IllegalStateException("Retransmission timeout " + config.getRetransmissionTimeout()
						+ " must not be 0 or less!");
			}

			if (config.getMaxRetransmissionTimeout() <= 0) {
				throw new IllegalStateException("Maximum retransmission timeout " + config.getMaxRetransmissionTimeout()
						+ " must not be 0 or less!");
			}

			if (config.getMaxRetransmissions() < 1) {
				throw new IllegalStateException("Maximum retransmissions " + config.getMaxRetransmissions()
						+ " must not be less than 1!");
			}

			if (config.getRetransmissionRandomFactor() < 1.0F) {
				throw new IllegalStateException("Retransmission timeout random factor " + config.getRetransmissionRandomFactor()
						+ " must not be less than 1.0!");
			}

			if (config.getRetransmissionTimeoutScale() < 1.0F) {
				throw new IllegalStateException("Retransmission timeout scale factor " + config.getRetransmissionTimeoutScale()
						+ " must not be less than 1.0!");
			}

			if (config.getMaxTransmissionUnit() != null && config.getMaxTransmissionUnitLimit() != null) {
				int mtu = config.getMaxTransmissionUnit();
				int limit = config.getMaxTransmissionUnitLimit();
				if (mtu > limit) {
					throw new IllegalStateException(
							"MTU (" + mtu + " bytes) is larger than the limit (" + limit + " bytes)!");
				}
			}

			if (config.getDtlsRole() == DtlsRole.SERVER_ONLY
					&& config.getCertificateAuthenticationMode() == CertificateAuthenticationMode.NONE
					&& config.advancedCertificateVerifier != null) {
				throw new IllegalStateException(
						"configured certificate verifier is not used for client authentication mode NONE!");
			}

			Integer cidCodePoint = config.useDeprecatedCid();
			if (cidCodePoint != null) {
				ExtensionType cidType = ExtensionType.getExtensionTypeById(cidCodePoint);
				if (cidType == null) {
					throw new IllegalStateException(cidCodePoint + " code point is not supported for extensions!");
				}
				if (cidType != ExtensionType.CONNECTION_ID
						&& cidType.getReplacementType() != ExtensionType.CONNECTION_ID) {
					throw new IllegalStateException(
							cidCodePoint + " (" + cidType + ") is no supported CID extension code point!");
				}
			}

			if (config.supportedGroups == null) {
				config.supportedGroups = config.configuration.get(DtlsConfig.DTLS_CURVES);
			}
			if (config.supportedGroups == null) {
				config.supportedGroups = Collections.emptyList();
			}
			if (config.supportedSignatureAlgorithms == null) {
				config.supportedSignatureAlgorithms = Collections.emptyList();
			}
			if (config.cipherSuiteSelector == null && config.getDtlsRole() != DtlsRole.CLIENT_ONLY) {
				config.cipherSuiteSelector = new DefaultCipherSuiteSelector();
			}
			if (config.resumptionVerifier == null && config.useServerSessionId()
					&& config.getDtlsRole() != DtlsRole.CLIENT_ONLY) {
				config.resumptionVerifier = new ConnectionStoreResumptionVerifier();
			}

			if (config.supportedCipherSuites == null || config.supportedCipherSuites.isEmpty()) {
				config.supportedCipherSuites = config.configuration.get(DtlsConfig.DTLS_CIPHER_SUITES);
			}
			if (config.supportedCipherSuites == null || config.supportedCipherSuites.isEmpty()) {
				determineCipherSuitesFromConfig();
			}

			// check cipher consistency
			if (config.supportedCipherSuites.isEmpty()) {
				throw new IllegalStateException("Supported cipher suites must be set either "
						+ "explicitly or implicitly by means of setting the identity or PSK store");
			}
			for (CipherSuite cipherSuite : config.supportedCipherSuites) {
				if (!cipherSuite.isSupported()) {
					throw new IllegalStateException("cipher-suites " + cipherSuite + " is not supported by JVM!");
				}
			}

			boolean certifacte = false;
			boolean ecc = false;
			boolean psk = false;
			for (CipherSuite suite : config.supportedCipherSuites) {
				if (suite.isPskBased()) {
					verifyPskBasedCipherConfig(suite);
					psk = true;
				} else if (suite.requiresServerCertificateMessage()) {
					verifyCertificateBasedCipherConfig(suite);
					certifacte = true;
				}
				if (suite.isEccBased()) {
					ecc = true;
				}
			}

			if (!psk && config.advancedPskStore != null) {
				throw new IllegalStateException("Advanced PSK store set, but no PSK cipher suite!");
			}

			CertificateProvider provider = config.certificateIdentityProvider;
			NewAdvancedCertificateVerifier verifier = config.advancedCertificateVerifier;

			if (config.certificateConfigurationHelper == null) {
				CertificateConfigurationHelper helper = new CertificateConfigurationHelper();
				if (provider instanceof ConfigurationHelperSetup) {
					((ConfigurationHelperSetup) provider).setupConfigurationHelper(helper);
					config.certificateConfigurationHelper = helper;
				}
				if (verifier instanceof ConfigurationHelperSetup) {
					((ConfigurationHelperSetup) verifier).setupConfigurationHelper(helper);
					config.certificateConfigurationHelper = helper;
				}
			}
			if (ecc) {
				if (config.supportedSignatureAlgorithms.isEmpty()) {
					List<SignatureAndHashAlgorithm> algorithms = new ArrayList<>(SignatureAndHashAlgorithm.DEFAULT);
					if (config.certificateConfigurationHelper != null) {
						ListUtils.addIfAbsent(algorithms,
								config.certificateConfigurationHelper.getDefaultSignatureAndHashAlgorithms());
					}
					config.supportedSignatureAlgorithms = algorithms;
				}
				if (config.supportedGroups.isEmpty()) {
					List<SupportedGroup> defaultGroups = new ArrayList<>(SupportedGroup.getPreferredGroups());
					if (config.certificateConfigurationHelper != null) {
						ListUtils.addIfAbsent(defaultGroups,
								config.certificateConfigurationHelper.getDefaultSupportedGroups());
					}
					config.supportedGroups = defaultGroups;
				}
			} else {
				if (!config.supportedSignatureAlgorithms.isEmpty()) {
					throw new IllegalStateException(
							"supported signature and hash algorithms set, but no ecdhe based cipher suite!");
				}
				if (!config.supportedGroups.isEmpty()) {
					throw new IllegalStateException("supported groups set, but no ecdhe based cipher suite!");
				}
			}

			if (!certifacte) {
				if (provider != null) {
					throw new IllegalStateException("certificate identity set, but no certificate based cipher suite!");
				}
				if (config.advancedCertificateVerifier != null) {
					throw new IllegalStateException("certificate trust set, but no certificate based cipher suite!");
				}
			}

			if (config.useRecommendedSupportedGroupsOnly()) {
				verifyRecommendedSupportedGroupsOnly(config.supportedGroups);
			}

			if (config.certificateConfigurationHelper != null) {
				config.certificateConfigurationHelper
						.verifySignatureAndHashAlgorithmsConfiguration(config.supportedSignatureAlgorithms);
				config.certificateConfigurationHelper.verifySupportedGroupsConfiguration(config.supportedGroups);
				if (provider != null && provider.getSupportedCertificateTypes().contains(CertificateType.X_509)) {
					if (config.getDtlsRole() == DtlsRole.CLIENT_ONLY) {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(true)) {
							throw new IllegalStateException("certificate has no proper key usage for clients!");
						}
					} else if (config.getDtlsRole() == DtlsRole.SERVER_ONLY) {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(false)) {
							throw new IllegalStateException("certificate has no proper key usage for servers!");
						}
					} else {
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(true)) {
							throw new IllegalStateException("certificate has no proper key usage as clients!");
						}
						if (!config.certificateConfigurationHelper.canBeUsedForAuthentication(false)) {
							throw new IllegalStateException("certificate has no proper key usage as servers!");
						}
					}
				}
			}
			if (config.useHelloVerifyRequest() && !config.useHelloVerifyRequestForPsk()
					&& !CipherSuite.containsPskBasedCipherSuite(config.supportedCipherSuites)) {
				throw new IllegalArgumentException(
						"HELLO_VERIFY_REQUEST disabled for PSK, requires at least one PSK cipher suite!");
			}
			config.supportedCipherSuites = ListUtils.init(config.supportedCipherSuites);
			config.supportedGroups = ListUtils.init(config.supportedGroups);
			config.supportedSignatureAlgorithms = ListUtils.init(config.supportedSignatureAlgorithms);
			if (config.connectionIdGenerator == null) {
				Integer cidLength = config.configuration.get(DtlsConfig.DTLS_CONNECTION_ID_LENGTH);
				Integer cidNode = config.configuration.get(DtlsConfig.DTLS_CONNECTION_ID_NODE_ID);
				if (cidLength != null) {
					if (cidNode != null) {
						if (cidLength <= 4) {
							throw new IllegalStateException(cidLength
									+ " bytes are too small for multiple nodes CID! At least, 5 bytes are required.");
						}
						setConnectionIdGenerator(new MultiNodeConnectionIdGenerator(cidNode, cidLength));
					} else {
						setConnectionIdGenerator(new SingleNodeConnectionIdGenerator(cidLength));
					}
				}
			}
			return config;
		}

		private void verifyPskBasedCipherConfig(CipherSuite suite) {
			if (config.advancedPskStore == null) {
				throw new IllegalStateException("PSK store must be set for configured " + suite.name());
			}
			if (!config.advancedPskStore.hasEcdhePskSupported() && suite.isEccBased()) {
				throw new IllegalStateException("PSK store doesn't support ECDHE! " + suite.name());
			}
		}

		private void verifyCertificateBasedCipherConfig(CipherSuite suite) {
			if (config.certificateIdentityProvider == null) {
				if (config.getDtlsRole() != DtlsRole.CLIENT_ONLY) {
					throw new IllegalStateException("Identity must be set for configured " + suite.name());
				}
			} else if (config.certificateConfigurationHelper != null) {
				List<String> keyAlgorithms = config.certificateConfigurationHelper.getSupportedKeyAlgorithms();
				String algorithm = suite.getCertificateKeyAlgorithm().name();
				if (!keyAlgorithms.contains(algorithm)) {
					throw new IllegalStateException(
							"Keys must be " + algorithm + " capable for configured " + suite.name());
				}
			}
			if (config.getDtlsRole() == DtlsRole.CLIENT_ONLY
					|| config.getCertificateAuthenticationMode() != CertificateAuthenticationMode.NONE) {
				if (config.advancedCertificateVerifier == null) {
					throw new IllegalStateException("certificate verifier must be set for configured " + suite.name());
				}
			}
		}

		private void verifyRecommendedCipherSuitesOnly(List<CipherSuite> suites) {
			StringBuilder message = new StringBuilder();
			for (CipherSuite cipherSuite : suites) {
				if (!cipherSuite.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(cipherSuite.name());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended cipher suites " + message
						+ " used! (Requires to set recommendedCipherSuitesOnly to false.)");
			}
		}

		private void verifyRecommendedSupportedGroupsOnly(List<SupportedGroup> supportedGroups) {
			StringBuilder message = new StringBuilder();
			for (SupportedGroup group : supportedGroups) {
				if (!group.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(group.name());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended supported groups (curves) " + message
						+ " used! (Requires to set recommendedSupportedGroupsOnly to false.)");
			}
		}

		private void verifyRecommendedSignatureAndHashAlgorithmsOnly(
				List<SignatureAndHashAlgorithm> signatureAndHashAlgorithms) {
			StringBuilder message = new StringBuilder();
			for (SignatureAndHashAlgorithm signature : signatureAndHashAlgorithms) {
				if (!signature.isRecommended()) {
					if (message.length() > 0) {
						message.append(", ");
					}
					message.append(signature.getJcaName());
				}
			}
			if (message.length() > 0) {
				throw new IllegalArgumentException("Not recommended signature and hash algorithms " + message
						+ " used! (Requires to set recommendedSignatureAndHashAlgorithmsOnly to false.)");
			}
		}

		private void determineCipherSuitesFromConfig() {
			// user has not explicitly set cipher suites
			// try to guess his intentions from properties he has set
			List<CipherSuite> ciphers = new ArrayList<>();
			boolean certificates = config.certificateIdentityProvider != null
					|| config.advancedCertificateVerifier != null;
			if (certificates) {
				// currently only ECDSA is supported!
				ciphers.addAll(CipherSuite.getEcdsaCipherSuites(config.useRecommendedCipherSuitesOnly()));
			}

			if (config.advancedPskStore != null) {
				if (config.advancedPskStore.hasEcdhePskSupported()) {
					ciphers.addAll(CipherSuite.getCipherSuitesByKeyExchangeAlgorithm(
							config.useRecommendedCipherSuitesOnly(), KeyExchangeAlgorithm.ECDHE_PSK));
				}
				ciphers.addAll(CipherSuite.getCipherSuitesByKeyExchangeAlgorithm(
						config.useRecommendedCipherSuitesOnly(), KeyExchangeAlgorithm.PSK));
			}
			if (config.getPreselectedCipherSuites() != null) {
				List<CipherSuite> preselect = new ArrayList<>();
				for (CipherSuite cipherSuite : config.getPreselectedCipherSuites()) {
					if (ciphers.contains(cipherSuite)) {
						preselect.add(cipherSuite);
					}
				}
				ciphers = preselect;
			}
			config.supportedCipherSuites = ciphers;
		}
	}
}
