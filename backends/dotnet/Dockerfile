FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build
WORKDIR /src
COPY NuGet.Config ./
COPY ChronoMart.sln ./
COPY ChronoMart.Api/ChronoMart.Api.csproj ChronoMart.Api/
COPY ChronoMart.Api/packages.lock.json ChronoMart.Api/
COPY ChronoMart.Api.Tests/ChronoMart.Api.Tests.csproj ChronoMart.Api.Tests/
COPY ChronoMart.Api.Tests/packages.lock.json ChronoMart.Api.Tests/
RUN dotnet restore ChronoMart.sln --locked-mode
COPY . .
RUN dotnet publish ChronoMart.Api/ChronoMart.Api.csproj \
    --configuration Release \
    --no-restore \
    --output /app/publish

FROM mcr.microsoft.com/dotnet/aspnet:9.0 AS runtime
WORKDIR /app
ENV ASPNETCORE_URLS=http://+:8102
EXPOSE 8102
COPY --from=build /app/publish .
USER $APP_UID
ENTRYPOINT ["dotnet", "ChronoMart.Api.dll"]
