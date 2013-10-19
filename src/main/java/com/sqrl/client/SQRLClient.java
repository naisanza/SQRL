package com.sqrl.client;

import java.io.IOException;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Date;

import com.sqrl.SQRLAuthentication;
import com.sqrl.SQRLIdentity;
import com.sqrl.SQRLPasswordParameters;
import com.sqrl.crypto.Curve25519;
import com.sqrl.crypto.HMACSHA256;
import com.sqrl.crypto.SCrypt;
import com.sqrl.crypto.SHA256;
import com.sqrl.exception.PasswordVerifyException;
import com.sqrl.exception.SQRLException;
import com.sqrl.utils.Bytes;
import com.sqrl.utils.URLs;

public class SQRLClient {
       
    public static void main(String[] args) throws GeneralSecurityException, SQRLException, IOException {
        
        String warning = "WARNING: THIS CODE IS REALLY REALLY REALLY EXPIREMENTAL, DO NOT USE FOR ANYTHING "
                       + "EXCEPT LEARNING HOW SOME OF THE CRYPTO BEHIND SQRL CAN BE IMPLEMENTED.";
        System.err.println(warning);
        
        // Because this code is so experimental, if its not being maintained anymore, then don't let it even run
        //  if the current date is great than January 1, 2014
        if ( new Date().getTime() >  1388556000L * 1000L ) {
            System.err.println();
            System.err.println("ERROR: THIS CODE IS TOO OLD!, if it had been maintained, "
                             + "this time check would have been removed");
            System.exit(-1);
        }
        
        /**
         *  @See com.sqrl.client.TestSQRLClient
         *  for examples of how to use this API
         */
    }

    public static SQRLIdentity exportMasterKey(SQRLIdentity identity, String password) throws SQRLException {
        // STEP 1: Scrypt the current password + passwordSalt
        // This is the expensive operation and its parameters should be tuned so
        // that this operation takes between 1-2 seconds to perform.
        byte[] scryptResult = SCrypt.scrypt(password, identity.getPasswordParameters());
        
        // STEP 2: Check the sha256 hash of the result from STEP 1 verse the
        // current stored passwordVerify value.
        byte[] passwordCheck = SHA256.digest(scryptResult);
        boolean passwordCheckSuccess = Bytes.arrayEqual(passwordCheck, identity.getPasswordVerify());
        if (!passwordCheckSuccess) {
            throw new PasswordVerifyException();
        }
        
        // STEP 3: XOR the master identity key from the SQRLIdentity with the
        // result from STEP 1 to create the original master key
        byte[] originalMasterKey = Bytes.xor(identity.getMasterIdentityKey(), scryptResult);
        
        // STEP 4: Create a new password salt
        byte[] newPasswordSalt = secureRandom(8); // 64-bit salt
        
        // STEP 5: SCrypt the current password and newPasswordSalt with WAY more difficult SCryptParameters
        SQRLPasswordParameters newPasswordParameters = new SQRLPasswordParameters(newPasswordSalt, 18, 8, 90);
        byte[] newScryptResult = SCrypt.scrypt(password, newPasswordParameters);
        
        // STEP 6: SHA256 the SCrypt result from STEP 5 to create the new password verifier
        byte[] newPasswordVerify = SHA256.digest(newScryptResult);
        
        // STEP 7: XOR the original master key with the SCrypt result from STEP 5 to create the new master identity key
        byte[] newMasterIdentityKey = Bytes.xor(originalMasterKey, newScryptResult);
        
        // Return a new SQRLIdentity with the new password salt, password verify, password parameters 
        //  and master identity key
        return new SQRLIdentity(identity.getIdentityName(), newMasterIdentityKey, newPasswordVerify, newPasswordParameters);
    }
    
    public static SQRLIdentity changePassword(SQRLIdentity identity, String currentPassword, String newPassword) throws SQRLException {
        // STEP 1: Scrypt the current password + passwordSalt
        // This is the expensive operation and its parameters should be tuned so
        // that this operation takes between 1-2 seconds to perform.
        byte[] scryptResult = SCrypt.scrypt(currentPassword, identity.getPasswordParameters());

        // STEP 2: Check the sha256 hash of the result from STEP 1 verse the
        // current stored passwordVerify value.
        byte[] passwordCheck = SHA256.digest(scryptResult);
        boolean passwordCheckSuccess = Bytes.arrayEqual(passwordCheck, identity.getPasswordVerify());
        if (!passwordCheckSuccess) {
            throw new PasswordVerifyException();
        }
        
        // STEP 3: XOR the master identity key from the SQRLIdentity with the
        // result from STEP 1 to create the original master key
        byte[] originalMasterKey = Bytes.xor(identity.getMasterIdentityKey(), scryptResult);
        
        // STEP 4: Create a new password salt
        byte[] newPasswordSalt = secureRandom(8); // 64-bit salt

        // STEP 5: SCrypt the newPassword and newPasswordSalt
        SQRLPasswordParameters newPasswordParameters = new SQRLPasswordParameters(newPasswordSalt, 16, 8, 12);
        byte[] newScryptResult = SCrypt.scrypt(newPassword, newPasswordParameters);
        
        // STEP 6: SHA256 the SCrypt result from STEP 5 to create the new password verifier
        byte[] newPasswordVerify = SHA256.digest(newScryptResult);

        // STEP 7: XOR the original master key with the SCrypt result from STEP 5 to create the new master identity key
        byte[] newMasterIdentityKey = Bytes.xor(originalMasterKey, newScryptResult);
        
        // Return a new SQRLIdentity with the new password salt, password verify, and master identity key
        // Note: the password is not permanently changed until this new identity object is written over the
        //       old identity on disk.
        return new SQRLIdentity(identity.getIdentityName(), newMasterIdentityKey, newPasswordVerify, newPasswordParameters);
    }

    public static SQRLAuthentication createAuthentication(SQRLIdentity identity, String password, String siteURL) throws SQRLException {
        // STEP 1: Scrypt the password + passwordSalt
        // This is the expensive operation and its parameters should be tuned so
        // that this operation takes between 1-2 seconds to perform.
        byte[] scryptResult = SCrypt.scrypt(password, identity.getPasswordParameters());

        // STEP 2: Check the sha256 hash of the result from STEP 1 verse the
        // stored passwordVerify value.
        byte[] passwordCheck = SHA256.digest(scryptResult);
        boolean passwordCheckSuccess = Bytes.arrayEqual(passwordCheck, identity.getPasswordVerify());
        if (!passwordCheckSuccess) {
            throw new PasswordVerifyException();
        }

        // STEP 3: XOR the master identity key from the SQRLIdentity with the
        // result from STEP 1 to create the original master key
        byte[] originalMasterKey = Bytes.xor(identity.getMasterIdentityKey(), scryptResult);

        // STEP 4: HMACSHA-256 the master key result from STEP 3: with the site TLD
        String sqrlRealm = URLs.getTLD(siteURL);
        byte[] privateKey = HMACSHA256.mac(originalMasterKey, sqrlRealm);

        // STEP 5: Synthesize a public key by using the result from STEP 4
        byte[] publicKey = Curve25519.publickey(privateKey);

        // STEP 6: Sign the entire site URL with the private key from STEP 4.
        byte[] signature = Curve25519.signature(siteURL.getBytes(Charset.forName("UTF-8")), privateKey, publicKey);

        // Return authentication object containing all the
        // outputs which are to be sent to the server.
        return new SQRLAuthentication(sqrlRealm, siteURL, signature, publicKey);
    }

    private static SecureRandom rand = new SecureRandom();
    private static byte[] secureRandom(int numBytes) {
        byte[] randBytes = new byte[numBytes];
        rand.nextBytes(randBytes);
        return randBytes;
    }
}
