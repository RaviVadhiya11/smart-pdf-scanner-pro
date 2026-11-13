-- DocNexa private cloud storage
-- Run this once in Supabase Dashboard > SQL Editor.
insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('docnexa-pdfs', 'docnexa-pdfs', false, 52428800, array['application/pdf'])
on conflict (id) do update set public = false, file_size_limit = 52428800,
  allowed_mime_types = array['application/pdf'];

create policy "DocNexa users upload own PDFs"
on storage.objects for insert to authenticated
with check (
  bucket_id = 'docnexa-pdfs'
  and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "DocNexa users read own PDFs"
on storage.objects for select to authenticated
using (
  bucket_id = 'docnexa-pdfs'
  and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "DocNexa users update own PDFs"
on storage.objects for update to authenticated
using (
  bucket_id = 'docnexa-pdfs'
  and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
  bucket_id = 'docnexa-pdfs'
  and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "DocNexa users delete own PDFs"
on storage.objects for delete to authenticated
using (
  bucket_id = 'docnexa-pdfs'
  and (storage.foldername(name))[1] = auth.uid()::text
);
