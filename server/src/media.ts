import { mkdir, rm, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';

import { RuntimeConfig } from './types';

/** Profile avatars and banners as files under MEDIA_DIR; server.ts serves them under /media/. */
export class ProfileMediaStorage {
  constructor(private readonly config: RuntimeConfig) {}

  objectKey(userId: string, kind: 'avatar' | 'banner', version: number, extension = 'jpg'): string {
    return `profiles/${userId}/${kind}/${version}.${extension}`;
  }

  publicUrl(key: string): string {
    return `${this.config.mediaPublicBaseUrl}/${key.split('/').map(encodeURIComponent).join('/')}`;
  }

  get enabled(): boolean {
    return Boolean(this.config.mediaDir && this.config.mediaPublicBaseUrl);
  }

  async upload(key: string, content: Buffer, _contentType = 'image/jpeg'): Promise<void> {
    if (!this.enabled) throw new Error('Profile media storage is not configured.');
    const path = join(this.config.mediaDir, ...key.split('/'));
    await mkdir(dirname(path), { recursive: true });
    await writeFile(path, content);
  }

  async remove(key: string): Promise<void> {
    if (!this.enabled) return;
    await rm(join(this.config.mediaDir, ...key.split('/')), { force: true });
  }
}
