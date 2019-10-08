FROM aeronetlab/scala-sbt

ADD . /tile_loader

WORKDIR /tile_loader

ENTRYPOINT ["sbt"]
CMD ["run"]
