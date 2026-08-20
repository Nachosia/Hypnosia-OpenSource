import { useState } from 'react';

interface Attachment {
  url: string;
  name: string;
  size?: number;
}

function isImage(name: string): boolean {
  const ext = name.split('.').pop()?.toLowerCase();
  return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(ext || '');
}

export default function AttachmentPreview({ attachments }: { attachments: Attachment[] }) {
  const [expanded, setExpanded] = useState<string | null>(null);

  if (!attachments || attachments.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-2 mt-2">
      {attachments.map((att, i) => {
        const img = isImage(att.name);
        return (
          <div key={i} className="relative">
            {img ? (
              <button
                onClick={() => setExpanded(expanded === att.url ? null : att.url)}
                className="block rounded overflow-hidden transition-opacity hover:opacity-90"
                style={{ border: '1px solid rgba(107,183,255,0.15)' }}
              >
                <img
                  src={att.url}
                  alt={att.name}
                  className="object-cover"
                  style={{ maxWidth: 180, maxHeight: 120, display: 'block' }}
                  loading="lazy"
                />
              </button>
            ) : (
              <a
                href={att.url}
                target="_blank"
                rel="noreferrer"
                className="font-mono text-[10px] px-2 py-1 rounded inline-flex items-center gap-1"
                style={{ color: '#6BB7FF', background: 'rgba(107,183,255,0.05)', border: '1px solid rgba(107,183,255,0.1)' }}
              >
                📎 {att.name}
              </a>
            )}
            {expanded === att.url && (
              <div
                className="fixed inset-0 z-50 flex items-center justify-center p-4"
                style={{ background: 'rgba(0,0,0,0.85)', backdropFilter: 'blur(4px)' }}
                onClick={() => setExpanded(null)}
              >
                <div className="relative max-w-full max-h-full">
                  <img
                    src={att.url}
                    alt={att.name}
                    className="rounded-lg"
                    style={{ maxWidth: '90vw', maxHeight: '85vh', objectFit: 'contain' }}
                  />
                  <p className="text-center font-mono text-[10px] mt-2" style={{ color: '#7A8A9E' }}>{att.name}</p>
                  <button
                    onClick={() => setExpanded(null)}
                    className="absolute -top-3 -right-3 w-8 h-8 rounded-full flex items-center justify-center font-bold text-sm"
                    style={{ background: '#ff6464', color: '#fff' }}
                  >
                    ×
                  </button>
                </div>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
