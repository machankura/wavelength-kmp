// WavelengthPasskey: the iOS WebAuthn transport for wavelength-kmp passkey
// wallets, built on AuthenticationServices (iOS 18+ for the PRF extension).
//
// Ported from @lightninglabs/wavelength-react-native's WavelengthPasskey.swift
// (MIT, Lightning Labs). It speaks the same WebAuthn JSON as Android
// Credential Manager, so the shared Kotlin PasskeyCeremony drives both.
//
// Usage:
//   let ceremony = PasskeyCeremony(
//       authenticator: PasskeyAuthenticatorCompanion.shared.fromCallbacks(callbacks: WavelengthPasskey()),
//       rpId: "example.com", timeout: ...)
//
// The app needs an associated-domains entitlement (webcredentials:<rpId>)
// and the domain must serve an apple-app-site-association file.

import AuthenticationServices
import CryptoKit
import Foundation
import UIKit
import WavelengthKit

public final class WavelengthPasskey: NSObject, CallbackPasskeyAuthenticator {
    private var inFlight: [AnyObject] = []

    public override init() {
        super.init()
    }

    public func isSupported() -> Bool {
        if #available(iOS 18.0, *) { return true }
        return false
    }

    public func create(requestJson: String, completion: @escaping (String?, String?) -> Void) {
        guard #available(iOS 18.0, *) else {
            completion(nil, "passkeys require iOS 18 or newer")
            return
        }
        guard
            let body = Self.parseJson(requestJson),
            let rp = body["rp"] as? [String: Any],
            let rpId = rp["id"] as? String,
            let user = body["user"] as? [String: Any],
            let userName = user["name"] as? String,
            let userIdB64 = user["id"] as? String,
            let userId = Self.dataFromBase64Url(userIdB64),
            let challengeB64 = body["challenge"] as? String,
            let challenge = Self.dataFromBase64Url(challengeB64),
            let salt = Self.prfSalt(body)
        else {
            completion(nil, "malformed passkey registration request")
            return
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
        let request = provider.createCredentialRegistrationRequest(challenge: challenge, name: userName, userID: userId)
        request.userVerificationPreference = .required
        request.prf = .inputValues(
            ASAuthorizationPublicKeyCredentialPRFRegistrationInput.InputValues(saltInput1: salt, saltInput2: nil))
        run(request: request, cancelMessage: "passkey registration was cancelled", completion: completion)
    }

    public func get(requestJson: String, completion: @escaping (String?, String?) -> Void) {
        guard #available(iOS 18.0, *) else {
            completion(nil, "passkeys require iOS 18 or newer")
            return
        }
        guard
            let body = Self.parseJson(requestJson),
            let rpId = body["rpId"] as? String,
            let challengeB64 = body["challenge"] as? String,
            let challenge = Self.dataFromBase64Url(challengeB64),
            let salt = Self.prfSalt(body)
        else {
            completion(nil, "malformed passkey assertion request")
            return
        }
        let provider = ASAuthorizationPlatformPublicKeyCredentialProvider(relyingPartyIdentifier: rpId)
        let request = provider.createCredentialAssertionRequest(challenge: challenge)
        request.userVerificationPreference = .required
        if let allowed = body["allowCredentials"] as? [[String: Any]] {
            request.allowedCredentials = allowed.compactMap { entry in
                guard let idB64 = entry["id"] as? String, let id = Self.dataFromBase64Url(idB64) else { return nil }
                return ASAuthorizationPlatformPublicKeyCredentialDescriptor(credentialID: id)
            }
        }
        request.prf = .inputValues(
            // Same InputValues type as the upstream helper uses for assertions.
            ASAuthorizationPublicKeyCredentialPRFRegistrationInput.InputValues(saltInput1: salt, saltInput2: nil))
        run(request: request, cancelMessage: "passkey authentication was cancelled", completion: completion)
    }

    @available(iOS 18.0, *)
    private func run(request: ASAuthorizationRequest, cancelMessage: String, completion: @escaping (String?, String?) -> Void) {
        let runner = PasskeyRunner(cancelMessage: cancelMessage, completion: completion)
        runner.onDone = { [weak self, weak runner] in
            self?.inFlight.removeAll { $0 === runner }
        }
        inFlight.append(runner)
        runner.run(request: request)
    }

    private static func prfSalt(_ body: [String: Any]) -> Data? {
        guard
            let extensions = body["extensions"] as? [String: Any],
            let prf = extensions["prf"] as? [String: Any],
            let eval = prf["eval"] as? [String: Any],
            let firstB64 = eval["first"] as? String
        else { return nil }
        return dataFromBase64Url(firstB64)
    }

    private static func parseJson(_ json: String) -> [String: Any]? {
        guard let data = json.data(using: .utf8) else { return nil }
        return (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    }

    static func base64Url(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    static func dataFromBase64Url(_ value: String) -> Data? {
        var s = value.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        while s.count % 4 != 0 { s += "=" }
        return Data(base64Encoded: s)
    }
}

@available(iOS 18.0, *)
private final class PasskeyRunner: NSObject, ASAuthorizationControllerDelegate,
    ASAuthorizationControllerPresentationContextProviding {
    private let cancelMessage: String
    private let completion: (String?, String?) -> Void
    var onDone: () -> Void = {}

    init(cancelMessage: String, completion: @escaping (String?, String?) -> Void) {
        self.cancelMessage = cancelMessage
        self.completion = completion
    }

    func run(request: ASAuthorizationRequest) {
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap { $0.windows }
            .first { $0.isKeyWindow } ?? ASPresentationAnchor()
    }

    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        switch authorization.credential {
        case let registration as ASAuthorizationPlatformPublicKeyCredentialRegistration:
            completion(Self.responseJson(credentialId: registration.credentialID,
                                         prfFirst: Self.prfData(registration.prf?.first)), nil)
        case let assertion as ASAuthorizationPlatformPublicKeyCredentialAssertion:
            completion(Self.responseJson(credentialId: assertion.credentialID,
                                         prfFirst: Self.prfData(assertion.prf?.first)), nil)
        default:
            completion(nil, "unexpected credential type from the authenticator")
        }
        onDone()
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let canceled = (error as? ASAuthorizationError)?.code == .canceled
        completion(nil, canceled ? cancelMessage : error.localizedDescription)
        onDone()
    }

    private static func prfData(_ key: SymmetricKey?) -> Data? {
        key?.withUnsafeBytes { Data($0) }
    }

    private static func responseJson(credentialId: Data, prfFirst: Data?) -> String? {
        var extensionResults: [String: Any] = [:]
        if let first = prfFirst {
            extensionResults = ["prf": ["results": ["first": WavelengthPasskey.base64Url(first)]]]
        }
        let body: [String: Any] = [
            "id": WavelengthPasskey.base64Url(credentialId),
            "rawId": WavelengthPasskey.base64Url(credentialId),
            "type": "public-key",
            "clientExtensionResults": extensionResults,
        ]
        guard let data = try? JSONSerialization.data(withJSONObject: body) else { return nil }
        return String(data: data, encoding: .utf8)
    }
}
