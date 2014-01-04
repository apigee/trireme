#include <stdio.h>
#include <string.h>
#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/evp.h>

/*
 * Build with: gcc -o evptest evptest.c -lcrypto
 */

static void printErrors(void)
{
  long err;
  char buf[128];

  do {
    err = ERR_get_error();
    if (err != 0) {
      ERR_error_string_n(err, buf, 128);
      fprintf(stderr, "%s", buf);
    }
  } while (err != 0);
}

int main(int argc, char** argv)
{
  const EVP_CIPHER* cipher;
  unsigned char key[EVP_MAX_KEY_LENGTH];
  unsigned char iv[EVP_MAX_IV_LENGTH];
  BIO* out;
  BIO* b64;

  if (argc != 4) {
    fprintf(stderr, "Usage: %s <cipher> <count> <passphrase>\n", argv[0]);
    return 2;
  }

  OpenSSL_add_all_ciphers();

  cipher = EVP_get_cipherbyname(argv[1]); 
  if (cipher == NULL) {
    fprintf(stderr, "Cipher \"%s\" not specified\n", argv[1]);
    printErrors();
    return 3;
  }
  
  int count = atoi(argv[2]);
  
  int keyLen = 
    EVP_BytesToKey(cipher, EVP_md5(), NULL,
                   argv[3], strlen(argv[3]), count,
                   key, iv);
  printf("Generated key is %i bytes\n", keyLen);
 
  b64 = BIO_new(BIO_f_base64());
  out = BIO_new_fp(stdout, BIO_NOCLOSE);
  out = BIO_push(b64, out);
  BIO_write(out, key, keyLen);
  BIO_flush(out);

  return 0;
}

