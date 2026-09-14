DO $$
BEGIN
    IF uuid_extract_version(uuidv7()) <> 7 THEN
        RAISE EXCEPTION 'PostgreSQL uuidv7() 未生成 UUIDv7';
    END IF;
END
$$;
