import base64
import os
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives import padding
from cryptography.hazmat.backends import default_backend

# Must match the Android key exactly
# Read from environment variable (set in .env file on server)
_key_str = os.environ.get("AES_SECRET_KEY", "")
if not _key_str:
    raise RuntimeError("AES_SECRET_KEY environment variable is not set. "
                       "Please set it in the .env file on the server.")
SECRET_KEY = _key_str.encode("utf-8")

def decrypt(cipher_text: str) -> str:
    if not cipher_text:
        return cipher_text
    
    if not cipher_text.startswith("ENC:"):
        return cipher_text
    
    try:
        b64_str = cipher_text[4:]
        combined = base64.b64decode(b64_str)
        
        iv = combined[:16]
        encrypted_bytes = combined[16:]
        
        cipher = Cipher(algorithms.AES(SECRET_KEY), modes.CBC(iv), backend=default_backend())
        decryptor = cipher.decryptor()
        
        padded_plain_text = decryptor.update(encrypted_bytes) + decryptor.finalize()
        
        unpadder = padding.PKCS7(128).unpadder()
        plain_text = unpadder.update(padded_plain_text) + unpadder.finalize()
        
        return plain_text.decode('utf-8')
    except Exception as e:
        print(f"Decryption failed: {e}")
        return cipher_text

def encrypt(plain_text: str) -> str:
    if not plain_text:
        return ""
    if plain_text.startswith("ENC:"):
        return plain_text
        
    try:
        iv = os.urandom(16)
        
        padder = padding.PKCS7(128).padder()
        padded_data = padder.update(plain_text.encode('utf-8')) + padder.finalize()
        
        cipher = Cipher(algorithms.AES(SECRET_KEY), modes.CBC(iv), backend=default_backend())
        encryptor = cipher.encryptor()
        
        encrypted_bytes = encryptor.update(padded_data) + encryptor.finalize()
        
        combined = iv + encrypted_bytes
        return "ENC:" + base64.b64encode(combined).decode('utf-8')
    except Exception as e:
        print(f"Encryption failed: {e}")
        return plain_text
