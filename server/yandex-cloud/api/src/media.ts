import {
  DeleteObjectCommand,
  PutObjectCommand,
  S3Client,
} from '@aws-sdk/client-s3';

import { RuntimeConfig } from './types';

export class ProfileMediaStorage {
  private readonly client: S3Client;

  constructor(private readonly config: RuntimeConfig) {
    this.client = new S3Client({
      endpoint: config.mediaEndpoint,
      region: 'ru-central1',
      forcePathStyle: true,
      credentials: {
        accessKeyId: config.mediaAccessKeyId,
        secretAccessKey: config.mediaSecretAccessKey,
      },
    });
  }

  objectKey(userId: string, kind: 'avatar' | 'banner', version: number): string {
    return `profiles/${userId}/${kind}/${version}.jpg`;
  }

  publicUrl(key: string): string {
    return `${this.config.mediaPublicBaseUrl}/${key.split('/').map(encodeURIComponent).join('/')}`;
  }

  async upload(key: string, content: Buffer): Promise<void> {
    await this.client.send(
      new PutObjectCommand({
        Bucket: this.config.mediaBucket,
        Key: key,
        Body: content,
        ContentType: 'image/jpeg',
        CacheControl: 'public, max-age=31536000, immutable',
      }),
    );
  }

  async remove(key: string): Promise<void> {
    await this.client.send(
      new DeleteObjectCommand({ Bucket: this.config.mediaBucket, Key: key }),
    );
  }
}
